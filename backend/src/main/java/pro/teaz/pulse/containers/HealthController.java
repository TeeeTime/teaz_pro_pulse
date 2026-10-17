package pro.teaz.pulse.containers;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Health of every app, for other Teaz apps (e.g. Portal's status tile).
 * Any logged-in user may read it, cross-origin from the origins in
 * {@code pulse.cors.allowed-origins}.
 */
@RestController
public class HealthController {

	private final ContainerService service;

	public HealthController(ContainerService service) {
		this.service = service;
	}

	@GetMapping("/api/health")
	public HealthSummary health() {
		return service.health();
	}
}
