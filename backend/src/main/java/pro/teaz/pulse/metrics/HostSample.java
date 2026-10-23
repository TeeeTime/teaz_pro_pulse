package pro.teaz.pulse.metrics;

/**
 * One reading of the whole server: everything running on it, Teaz or not.
 * CPU is a running total; its usage comes from the difference between two readings.
 *
 * @param cpuBusyTicks  CPU time spent working so far, all cores together (/proc/stat, minus idle and iowait)
 * @param cpuTotalTicks CPU time so far, all cores together, busy or not
 * @param cores         number of CPU cores
 * @param load          load average over 1, 5 and 15 minutes
 * @param memoryTotal   bytes of RAM
 * @param memoryUsed    bytes in use: total minus what the kernel reports as available
 * @param diskTotal     bytes on the filesystem holding Docker's data (normally the server's root disk)
 * @param diskUsed      bytes used on it
 */
public record HostSample(
		long cpuBusyTicks,
		long cpuTotalTicks,
		int cores,
		double[] load,
		long memoryTotal,
		long memoryUsed,
		long diskTotal,
		long diskUsed) {
}
