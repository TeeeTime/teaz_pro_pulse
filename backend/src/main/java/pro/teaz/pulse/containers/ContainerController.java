package pro.teaz.pulse.containers;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Pulse's own UI. Requires the pulse-admin role in prod, see SecurityConfig. */
@RestController
@RequestMapping("/api/containers")
public class ContainerController {

	private final ContainerService service;

	public ContainerController(ContainerService service) {
		this.service = service;
	}

	@GetMapping
	public List<ContainerView> list() {
		return service.containers();
	}

	@PostMapping("/{id}/start")
	public ResponseEntity<Void> start(@PathVariable String id) {
		service.perform(id, ContainerAction.START);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{id}/stop")
	public ResponseEntity<Void> stop(@PathVariable String id) {
		service.perform(id, ContainerAction.STOP);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/{id}/restart")
	public ResponseEntity<Void> restart(@PathVariable String id) {
		service.perform(id, ContainerAction.RESTART);
		return ResponseEntity.noContent().build();
	}
}
