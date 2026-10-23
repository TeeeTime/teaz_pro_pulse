package pro.teaz.pulse.metrics;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The Performance view. Requires the pulse-admin role in prod, see SecurityConfig. */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

	private final MetricsSampler sampler;

	public MetricsController(MetricsSampler sampler) {
		this.sampler = sampler;
	}

	/** The last measurement with the history; 503 until the first one right after startup. */
	@GetMapping
	public MetricsView metrics() {
		return sampler.latest().orElseThrow(() -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
				"Pulse is taking its first measurements, try again in a few seconds."));
	}
}
