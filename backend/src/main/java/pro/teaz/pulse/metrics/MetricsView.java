package pro.teaz.pulse.metrics;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/metrics}: the server's load, the Teaz containers' share of it
 * and the rest, as of the last measurement. CPU is always a share of the
 * whole server (all cores = 100%), so the Teaz containers and "other" add up
 * to the server's value. Values that need two measurements (CPU, rates) are
 * null right after Pulse started.
 *
 * @param sampledAt       when this was measured
 * @param intervalSeconds time between two measurements
 * @param teaz            all running Teaz containers together
 * @param other           everything else on the server (the server minus the Teaz containers), not listed one by one
 * @param apps            running Teaz containers per app, by app name
 * @param history         one point per measurement, oldest first, over {@code pulse.metrics.history}
 */
public record MetricsView(
		Instant sampledAt,
		long intervalSeconds,
		Host host,
		Usage teaz,
		Usage other,
		List<AppUsage> apps,
		List<Point> history) {

	/**
	 * @param load            load average over 1, 5 and 15 minutes
	 * @param memoryUsedBytes RAM in use (total minus available)
	 * @param diskUsedBytes   on the filesystem holding Docker's data
	 */
	public record Host(
			int cores,
			Double cpuPercent,
			double[] load,
			long memoryUsedBytes,
			long memoryTotalBytes,
			long diskUsedBytes,
			long diskTotalBytes) {
	}

	public record Usage(Double cpuPercent, long memoryBytes) {
	}

	public record AppUsage(String name, Double cpuPercent, long memoryBytes, List<ContainerUsage> containers) {
	}

	/**
	 * @param memoryLimitBytes the container's own memory limit, null without one
	 * @param netRxPerSecond   bytes per second over all its networks
	 * @param blockReadPerSecond bytes per second from block devices
	 */
	public record ContainerUsage(
			String id,
			String name,
			String service,
			Double cpuPercent,
			long memoryBytes,
			Long memoryLimitBytes,
			Double netRxPerSecond,
			Double netTxPerSecond,
			Double blockReadPerSecond,
			Double blockWritePerSecond) {
	}

	public record Point(Instant at, double hostCpuPercent, double teazCpuPercent, long hostMemoryBytes,
			long teazMemoryBytes) {
	}
}
