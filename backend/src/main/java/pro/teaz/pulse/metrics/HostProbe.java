package pro.teaz.pulse.metrics;

/**
 * Reads the server's overall load. {@link ProcHostProbe} on the VPS,
 * {@link MockHostProbe} in local dev; see {@link MetricsConfig}.
 */
public interface HostProbe {

	HostSample sample();
}
