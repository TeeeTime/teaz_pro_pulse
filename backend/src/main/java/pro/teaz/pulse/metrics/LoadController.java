package pro.teaz.pulse.metrics;

import java.time.Duration;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import pro.teaz.pulse.PulseProperties;

/**
 * The server's CPU and memory charts, for other Teaz apps (e.g. a Portal tile).
 * Any logged-in user may read it, cross-origin from the origins in
 * {@code pulse.cors.allowed-origins}.
 */
@RestController
public class LoadController {

	private final MetricsSampler sampler;
	private final Duration history;

	public LoadController(MetricsSampler sampler, PulseProperties properties) {
		this.sampler = sampler;
		this.history = properties.metrics().history();
	}

	/** 503 until the first measurement right after startup. */
	@GetMapping("/api/load")
	public LoadSummary load() {
		return sampler.latest().map(metrics -> LoadSummary.of(metrics, history))
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
						"Pulse is taking its first measurements, try again in a few seconds."));
	}
}
