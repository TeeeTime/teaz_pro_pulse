package pro.teaz.pulse.metrics;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The host's load from Linux's /proc, read from inside Pulse's container.
 * /proc/stat, /proc/meminfo and /proc/loadavg aren't namespaced by Docker:
 * a container sees the whole server's values there, so no extra mounts or
 * privileges are needed. The disk is the filesystem under the container's
 * own root, which is where Docker keeps its data (usually the server's root
 * disk).
 */
public class ProcHostProbe implements HostProbe {

	private final Path proc;
	private final Path disk;

	public ProcHostProbe(Path proc, Path disk) {
		this.proc = proc;
		this.disk = disk;
	}

	@Override
	public HostSample sample() {
		try {
			List<String> stat = Files.readAllLines(proc.resolve("stat"));
			long[] cpu = cpuTicks(stat.getFirst());
			int cores = (int) stat.stream().filter(line -> line.matches("cpu\\d+ .*")).count();

			long memTotal = 0;
			long memAvailable = 0;
			for (String line : Files.readAllLines(proc.resolve("meminfo"))) {
				if (line.startsWith("MemTotal:")) memTotal = kilobytes(line);
				else if (line.startsWith("MemAvailable:")) memAvailable = kilobytes(line);
			}

			String[] loadavg = Files.readString(proc.resolve("loadavg")).trim().split("\\s+");
			double[] load = {Double.parseDouble(loadavg[0]), Double.parseDouble(loadavg[1]), Double.parseDouble(loadavg[2])};

			FileStore store = Files.getFileStore(disk);
			long diskTotal = store.getTotalSpace();

			return new HostSample(cpu[0], cpu[1], Math.max(1, cores), load, memTotal, memTotal - memAvailable,
					diskTotal, diskTotal - store.getUnallocatedSpace());
		} catch (IOException e) {
			throw new UncheckedIOException("Reading the host's load from " + proc + " failed", e);
		}
	}

	/**
	 * The aggregate "cpu" line: user nice system idle iowait irq softirq steal
	 * (guest time is already counted in user). Waiting for I/O counts as idle.
	 *
	 * @return busy and total ticks
	 */
	static long[] cpuTicks(String line) {
		String[] fields = line.trim().split("\\s+");
		long total = 0;
		for (int i = 1; i <= Math.min(8, fields.length - 1); i++) total += Long.parseLong(fields[i]);
		long idle = Long.parseLong(fields[4]) + (fields.length > 5 ? Long.parseLong(fields[5]) : 0);
		return new long[] {total - idle, total};
	}

	/** "MemTotal:       16314500 kB" -> bytes */
	private static long kilobytes(String line) {
		return Long.parseLong(line.replaceAll("[^0-9]", "")) * 1024;
	}
}
