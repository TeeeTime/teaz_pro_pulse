package pro.teaz.pulse.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import pro.teaz.pulse.PulseProperties;
import pro.teaz.pulse.docker.ContainerStats;
import pro.teaz.pulse.docker.DockerClient;
import pro.teaz.pulse.docker.DockerContainer;

class MetricsSamplerTests {

	private static final long GB = 1L << 30;

	/** Containers and their current counters, changed by the tests between rounds. */
	static class FakeDocker implements DockerClient {
		List<DockerContainer> containers = List.of();
		Map<String, ContainerStats> stats = new HashMap<>();

		@Override
		public List<DockerContainer> listManagedContainers() {
			return containers;
		}

		@Override
		public ContainerStats stats(String id) {
			ContainerStats s = stats.get(id);
			if (s == null) throw new IllegalStateException("gone");
			return s;
		}

		@Override
		public void start(String id) {
		}

		@Override
		public void stop(String id) {
		}

		@Override
		public void restart(String id) {
		}
	}

	static class FakeClock extends Clock {
		Instant now = Instant.parse("2026-10-23T12:00:00Z");

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}
	}

	FakeDocker docker;
	FakeClock clock;
	HostSample host;
	MetricsSampler sampler;

	static DockerContainer container(String id, String app, String service, String state) {
		return new DockerContainer(id, app.toLowerCase() + "-" + service, app, service, "img", state, null, null, null,
				0, false);
	}

	/** Host counters in ticks: busy and total; memory in GB. */
	static HostSample host(long busy, long total, long memoryUsedGb) {
		return new HostSample(busy, total, 4, new double[] {1.0, 0.8, 0.5}, 16 * GB, memoryUsedGb * GB, 100 * GB,
				40 * GB);
	}

	/** cpu and system in seconds of CPU time, memory in GB, I/O counters in bytes. */
	static ContainerStats stats(double cpu, double system, double memoryGb, long limit, long io) {
		return new ContainerStats((long) (cpu * 1e9), (long) (system * 1e9), (long) (memoryGb * GB), limit, io, io / 2,
				io / 4, io / 8);
	}

	@BeforeEach
	void setUp() {
		docker = new FakeDocker();
		clock = new FakeClock();
		host = host(0, 0, 6);
		PulseProperties properties = new PulseProperties(null, new PulseProperties.Cors(List.of()),
				new PulseProperties.Metrics(Duration.ofSeconds(10), Duration.ofMinutes(1)));
		sampler = new MetricsSampler(docker, () -> host, properties, clock);
	}

	@Test
	void nothingBeforeTheFirstMeasurement() {
		assertThat(sampler.latest()).isEmpty();
	}

	@Test
	void firstMeasurementHasMemoryButNoCpuYet() {
		docker.containers = List.of(container("a", "Portal", "backend", "running"));
		docker.stats.put("a", stats(1, 100, 0.5, 16 * GB, 0));

		sampler.sample();

		MetricsView view = sampler.latest().orElseThrow();
		assertThat(view.host().cpuPercent()).isNull();
		assertThat(view.teaz().cpuPercent()).isNull();
		assertThat(view.teaz().memoryBytes()).isEqualTo(GB / 2);
		assertThat(view.other().memoryBytes()).isEqualTo(6 * GB - GB / 2);
		assertThat(view.apps().getFirst().containers().getFirst().netRxPerSecond()).isNull();
		assertThat(view.history()).isEmpty();
	}

	@Test
	void cpuIsEachContainersShareOfTheWholeServer() {
		docker.containers = List.of(
				container("p1", "Portal", "frontend", "running"),
				container("p2", "Portal", "backend", "running"),
				container("c1", "Calendar", "backend", "running"),
				container("x", "Calendar", "worker", "exited"));
		docker.stats.put("p1", stats(10, 1000, 0.1, 16 * GB, 1000));
		docker.stats.put("p2", stats(20, 1000, 1, 2 * GB, 0));
		docker.stats.put("c1", stats(30, 1000, 0.5, 16 * GB, 0));
		sampler.sample();

		// 10 s later: the host spent 400 s of CPU time (4 cores) of which 100 s working = 25%.
		clock.now = clock.now.plusSeconds(10);
		host = host(100, 400, 7);
		docker.stats.put("p1", stats(14, 1400, 0.1, 16 * GB, 21_000));  // 4 of 400 s: 1%
		docker.stats.put("p2", stats(40, 1400, 1, 2 * GB, 0));          // 20 of 400: 5%
		docker.stats.put("c1", stats(38, 1400, 0.5, 16 * GB, 0));       // 8 of 400: 2%
		sampler.sample();

		MetricsView view = sampler.latest().orElseThrow();
		assertThat(view.host().cpuPercent()).isCloseTo(25, within(1e-9));
		assertThat(view.host().cores()).isEqualTo(4);
		assertThat(view.teaz().cpuPercent()).isCloseTo(8, within(1e-9));
		assertThat(view.other().cpuPercent()).isCloseTo(17, within(1e-9));
		assertThat(view.teaz().memoryBytes()).isEqualTo((long) (1.6 * GB));
		assertThat(view.other().memoryBytes()).isEqualTo(7 * GB - (long) (1.6 * GB));

		// By app name; the stopped worker isn't measured.
		assertThat(view.apps()).extracting(MetricsView.AppUsage::name).containsExactly("Calendar", "Portal");
		MetricsView.AppUsage portal = view.apps().get(1);
		assertThat(portal.cpuPercent()).isCloseTo(6, within(1e-9));
		assertThat(portal.containers()).extracting(MetricsView.ContainerUsage::service).containsExactly("backend", "frontend");
		MetricsView.ContainerUsage frontend = portal.containers().get(1);
		assertThat(frontend.netRxPerSecond()).isCloseTo(2000, within(1e-9));
		assertThat(frontend.blockWritePerSecond()).isCloseTo(250, within(1e-9));
		// Only a real limit, not the host's memory.
		assertThat(frontend.memoryLimitBytes()).isNull();
		assertThat(portal.containers().get(0).memoryLimitBytes()).isEqualTo(2 * GB);

		assertThat(view.history()).singleElement().satisfies(point -> {
			assertThat(point.hostCpuPercent()).isCloseTo(25, within(1e-9));
			assertThat(point.teazCpuPercent()).isCloseTo(8, within(1e-9));
			assertThat(point.hostMemoryBytes()).isEqualTo(7 * GB);
		});
	}

	@Test
	void aRestartedContainerHasNoCpuForOneRound() {
		docker.containers = List.of(container("a", "Portal", "backend", "running"));
		docker.stats.put("a", stats(50, 1000, 0.5, 16 * GB, 5000));
		sampler.sample();

		clock.now = clock.now.plusSeconds(10);
		host = host(100, 400, 6);
		docker.stats.put("a", stats(1, 1400, 0.2, 16 * GB, 10));
		sampler.sample();

		MetricsView.ContainerUsage usage = sampler.latest().orElseThrow().apps().getFirst().containers().getFirst();
		assertThat(usage.cpuPercent()).isNull();
		assertThat(usage.netRxPerSecond()).isNull();
		assertThat(sampler.latest().orElseThrow().teaz().cpuPercent()).isZero();
	}

	@Test
	void aContainerWithoutStatsIsLeftOut() {
		docker.containers = List.of(container("a", "Portal", "backend", "running"),
				container("b", "Portal", "frontend", "running"));
		docker.stats.put("a", stats(1, 100, 0.5, 16 * GB, 0));

		sampler.sample();

		assertThat(sampler.latest().orElseThrow().apps().getFirst().containers())
				.extracting(MetricsView.ContainerUsage::id).containsExactly("a");
	}

	@Test
	void historyKeepsOnlyTheConfiguredWindow() {
		for (int i = 0; i <= 12; i++) {
			host = host(i * 100L, i * 400L, 6);
			sampler.sample();
			clock.now = clock.now.plusSeconds(10);
		}

		// 12 points with CPU (from the second measurement on), 120 s; one minute is kept.
		List<MetricsView.Point> history = sampler.latest().orElseThrow().history();
		assertThat(history).hasSize(7);
		assertThat(Duration.between(history.getFirst().at(), history.getLast().at())).isEqualTo(Duration.ofMinutes(1));
	}

	@Test
	void keepsTheLastMeasurementWhenOneFails() {
		sampler.sample();
		MetricsView before = sampler.latest().orElseThrow();

		docker.containers = null;
		clock.now = clock.now.plusSeconds(10);
		sampler.sample();

		assertThat(sampler.latest()).containsSame(before);
	}
}
