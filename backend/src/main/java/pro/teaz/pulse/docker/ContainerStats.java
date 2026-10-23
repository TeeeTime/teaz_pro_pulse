package pro.teaz.pulse.docker;

/**
 * One reading of a running container's resource counters (Docker's stats).
 * CPU and I/O are running totals; usage per second comes from the difference
 * between two readings (see MetricsSampler).
 *
 * @param cpuNanos         CPU time the container used so far, all cores together
 * @param systemCpuNanos   CPU time the whole host spent so far, all cores together (same clock as cpuNanos)
 * @param memoryBytes      memory in use, without the page cache the kernel can drop (as {@code docker stats})
 * @param memoryLimitBytes the container's memory limit; the host's memory when it has none
 * @param netRxBytes       received over all its networks so far
 * @param netTxBytes       sent over all its networks so far
 * @param blockReadBytes   read from block devices so far
 * @param blockWriteBytes  written to block devices so far
 */
public record ContainerStats(
		long cpuNanos,
		long systemCpuNanos,
		long memoryBytes,
		long memoryLimitBytes,
		long netRxBytes,
		long netTxBytes,
		long blockReadBytes,
		long blockWriteBytes) {
}
