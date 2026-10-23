package pro.teaz.pulse.metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/load}: the server's CPU and memory with the Teaz containers'
 * share of it, now and over {@code pulse.metrics.history}, for other apps
 * (Portal's load tile) to chart. The aggregate of {@link MetricsView} only:
 * deliberately no containers, IDs, disk or network.
 *
 * @param intervalSeconds  time between two history points
 * @param historySeconds   how far back {@code history} goes at most (the chart's x axis)
 * @param cores            CPU cores of the server; CPU is a share of all of them (all cores = 100%)
 * @param memoryTotalBytes RAM of the server (the memory chart's y axis)
 * @param host             the whole server; cpuPercent is null right after Pulse started
 * @param teaz             all running Teaz containers together, part of {@code host}
 * @param history          one point per measurement, oldest first
 */
public record LoadSummary(
		Instant sampledAt,
		long intervalSeconds,
		long historySeconds,
		int cores,
		long memoryTotalBytes,
		MetricsView.Usage host,
		MetricsView.Usage teaz,
		List<MetricsView.Point> history) {

	static LoadSummary of(MetricsView metrics, Duration history) {
		MetricsView.Host host = metrics.host();
		return new LoadSummary(
				metrics.sampledAt(),
				metrics.intervalSeconds(),
				history.toSeconds(),
				host.cores(),
				host.memoryTotalBytes(),
				new MetricsView.Usage(host.cpuPercent(), host.memoryUsedBytes()),
				metrics.teaz(),
				metrics.history());
	}
}
