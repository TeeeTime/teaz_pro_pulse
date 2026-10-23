package pro.teaz.pulse.metrics;

import java.time.Duration;
import java.time.Instant;

import pro.teaz.pulse.docker.MockDockerClient;

/**
 * Local-dev stand-in for {@link ProcHostProbe}: a made-up server with the
 * size {@link MockDockerClient} measures against, busier than the Teaz
 * containers alone, so "other" usage shows up too.
 */
public class MockHostProbe implements HostProbe {

	private static final int TICKS_PER_SECOND = 100;
	private final Instant created = Instant.now();

	@Override
	public HostSample sample() {
		double seconds = Duration.between(created, Instant.now()).toMillis() / 1000.0;
		long total = (long) (seconds * MockDockerClient.CORES * TICKS_PER_SECOND);
		// About 35% busy, drifting by a few points over minutes.
		double busySeconds = 0.35 * seconds + 6 * Math.sin(seconds / 90);
		long busy = (long) (Math.max(0, busySeconds) * MockDockerClient.CORES * TICKS_PER_SECOND);
		long memoryUsed = (long) ((3.4 + 0.2 * Math.sin(seconds / 240)) * (1L << 30));
		return new HostSample(Math.min(busy, total), total, MockDockerClient.CORES,
				new double[] {1.32, 1.18, 0.97}, MockDockerClient.MEMORY, memoryUsed, 80L << 30, 31L << 30);
	}
}
