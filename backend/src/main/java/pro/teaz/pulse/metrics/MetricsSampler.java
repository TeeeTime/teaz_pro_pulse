package pro.teaz.pulse.metrics;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.ToLongFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import pro.teaz.pulse.PulseProperties;
import pro.teaz.pulse.docker.ContainerStats;
import pro.teaz.pulse.docker.DockerClient;
import pro.teaz.pulse.docker.DockerContainer;

/**
 * Measures the server and every running Teaz container (those with
 * {@value DockerContainer#APP_LABEL}) each {@code pulse.metrics.interval},
 * whether anyone is looking or not, and keeps {@code pulse.metrics.history}
 * of it in memory (gone after a restart; nothing is stored).
 *
 * CPU and I/O are counters, so each value is the difference to the previous
 * measurement. A container's CPU is its share of the whole server's CPU time
 * (Docker counts both on the same clock), so the containers, "other" and the
 * server's total are on one scale. Containers without the label are never
 * looked at one by one; they're part of "other", the server's total minus the
 * Teaz containers.
 */
@Service
public class MetricsSampler {

	private static final Logger log = LoggerFactory.getLogger(MetricsSampler.class);

	private static final Comparator<DockerContainer> ORDER = Comparator
			.comparing(DockerContainer::app, String.CASE_INSENSITIVE_ORDER)
			.thenComparing(DockerContainer::service, String.CASE_INSENSITIVE_ORDER);

	/** What one measurement read, kept to compute the next one's differences. */
	private record Reading(Instant at, HostSample host, Map<String, ContainerStats> containers) {
	}

	private final DockerClient docker;
	private final HostProbe host;
	private final PulseProperties.Metrics settings;
	private final Clock clock;
	private final Deque<MetricsView.Point> history = new ArrayDeque<>();
	private Reading previous;
	private volatile MetricsView latest;
	private boolean failing;

	public MetricsSampler(DockerClient docker, HostProbe host, PulseProperties properties, Clock clock) {
		this.docker = docker;
		this.host = host;
		this.settings = properties.metrics();
		this.clock = clock;
	}

	/** The last measurement; empty until the first one is done. */
	public Optional<MetricsView> latest() {
		return Optional.ofNullable(latest);
	}

	@Scheduled(fixedDelayString = "${pulse.metrics.interval:10s}")
	public synchronized void sample() {
		try {
			Instant now = clock.instant();
			HostSample server = host.sample();
			List<DockerContainer> running = docker.listManagedContainers().stream()
					.filter(DockerContainer::running)
					.sorted(ORDER)
					.toList();
			Reading current = new Reading(now, server, stats(running));
			MetricsView view = view(previous, current, running);

			if (view.host().cpuPercent() != null && view.teaz().cpuPercent() != null) {
				history.addLast(new MetricsView.Point(now, view.host().cpuPercent(), view.teaz().cpuPercent(),
						server.memoryUsed(), view.teaz().memoryBytes()));
			}
			Instant oldest = now.minus(settings.history());
			while (!history.isEmpty() && history.peekFirst().at().isBefore(oldest)) history.removeFirst();

			latest = new MetricsView(view.sampledAt(), view.intervalSeconds(), view.host(), view.teaz(), view.other(),
					view.apps(), List.copyOf(history));
			previous = current;
			if (failing) log.info("Measuring the server works again.");
			failing = false;
		} catch (RuntimeException e) {
			// Every interval while Docker or /proc is unreachable: log it once, keep the last measurement.
			if (!failing) log.warn("Measuring the server failed; retrying every {}", settings.interval(), e);
			failing = true;
		}
	}

	/** Every container's stats at once; one that fails (stopped in between) is left out of this round. */
	private Map<String, ContainerStats> stats(List<DockerContainer> running) {
		Map<String, ContainerStats> result = new LinkedHashMap<>();
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			Map<String, Future<ContainerStats>> futures = new LinkedHashMap<>();
			for (DockerContainer c : running) futures.put(c.id(), executor.submit(() -> docker.stats(c.id())));
			for (Map.Entry<String, Future<ContainerStats>> entry : futures.entrySet()) {
				try {
					ContainerStats stats = entry.getValue().get();
					if (stats != null) result.put(entry.getKey(), stats);
				} catch (ExecutionException e) {
					log.debug("No stats for container {}: {}", entry.getKey(), e.getCause().getMessage());
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
		return result;
	}

	private MetricsView view(Reading before, Reading now, List<DockerContainer> running) {
		HostSample server = now.host();
		Double hostCpu = null;
		double seconds = 0;
		if (before != null) {
			seconds = Duration.between(before.at(), now.at()).toMillis() / 1000.0;
			long total = server.cpuTotalTicks() - before.host().cpuTotalTicks();
			long busy = server.cpuBusyTicks() - before.host().cpuBusyTicks();
			if (total > 0 && busy >= 0) hostCpu = Math.min(100, 100.0 * busy / total);
		}

		Map<String, List<MetricsView.ContainerUsage>> byApp = new LinkedHashMap<>();
		for (DockerContainer c : running) {
			ContainerStats stats = now.containers().get(c.id());
			if (stats == null) continue;
			ContainerStats last = before == null ? null : before.containers().get(c.id());
			Double cpu = null;
			if (last != null) {
				long system = stats.systemCpuNanos() - last.systemCpuNanos();
				long used = stats.cpuNanos() - last.cpuNanos();
				// A negative difference: the container restarted and its counters began again.
				if (system > 0 && used >= 0) cpu = 100.0 * used / system;
			}
			boolean limited = stats.memoryLimitBytes() > 0 && stats.memoryLimitBytes() < server.memoryTotal();
			byApp.computeIfAbsent(c.app(), app -> new ArrayList<>()).add(new MetricsView.ContainerUsage(
					c.id(), c.name(), c.service(), cpu, stats.memoryBytes(),
					limited ? stats.memoryLimitBytes() : null,
					rate(last, stats, seconds, ContainerStats::netRxBytes),
					rate(last, stats, seconds, ContainerStats::netTxBytes),
					rate(last, stats, seconds, ContainerStats::blockReadBytes),
					rate(last, stats, seconds, ContainerStats::blockWriteBytes)));
		}

		List<MetricsView.AppUsage> apps = new ArrayList<>();
		double teazCpu = 0;
		long teazMemory = 0;
		for (Map.Entry<String, List<MetricsView.ContainerUsage>> entry : byApp.entrySet()) {
			Double appCpu = sum(entry.getValue());
			long appMemory = entry.getValue().stream().mapToLong(MetricsView.ContainerUsage::memoryBytes).sum();
			apps.add(new MetricsView.AppUsage(entry.getKey(), appCpu, appMemory, entry.getValue()));
			if (appCpu != null) teazCpu += appCpu;
			teazMemory += appMemory;
		}

		Double teaz = hostCpu == null ? null : Math.min(teazCpu, hostCpu);
		return new MetricsView(
				now.at(),
				settings.interval().toSeconds(),
				new MetricsView.Host(server.cores(), hostCpu, server.load(), server.memoryUsed(), server.memoryTotal(),
						server.diskUsed(), server.diskTotal()),
				new MetricsView.Usage(teaz, teazMemory),
				new MetricsView.Usage(hostCpu == null ? null : hostCpu - teaz, Math.max(0, server.memoryUsed() - teazMemory)),
				apps,
				List.of());
	}

	private static Double sum(List<MetricsView.ContainerUsage> containers) {
		Double total = null;
		for (MetricsView.ContainerUsage c : containers) {
			if (c.cpuPercent() != null) total = (total == null ? 0 : total) + c.cpuPercent();
		}
		return total;
	}

	private static Double rate(ContainerStats before, ContainerStats now, double seconds,
			ToLongFunction<ContainerStats> counter) {
		if (before == null || seconds <= 0) return null;
		long difference = counter.applyAsLong(now) - counter.applyAsLong(before);
		return difference < 0 ? null : difference / seconds;
	}
}
