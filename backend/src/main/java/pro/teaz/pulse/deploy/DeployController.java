package pro.teaz.pulse.deploy;

import java.security.Principal;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deploy button: {@code git pull --ff-only} and {@code docker compose up -d --build}
 * for one compose project. Requires the pulse-admin role in prod, see SecurityConfig.
 */
@RestController
@RequestMapping("/api/deploys")
public class DeployController {

	private final DeployService service;

	public DeployController(DeployService service) {
		this.service = service;
	}

	@GetMapping
	public List<DeployJob> jobs() {
		return service.jobs();
	}

	@GetMapping("/{project}")
	public DeployJob job(@PathVariable String project) {
		return service.job(project);
	}

	/** 202: started; poll {@code GET /api/deploys/{project}} for the log until it's no longer running. */
	@PostMapping("/{project}")
	public ResponseEntity<DeployJob> deploy(@PathVariable String project, Principal principal) {
		return ResponseEntity.accepted().body(service.deploy(project, userName(principal)));
	}

	/** Keycloak's user name rather than the token's subject UUID; "local" in dev without auth. */
	static String userName(Principal principal) {
		if (principal instanceof JwtAuthenticationToken jwt) {
			String name = jwt.getToken().getClaimAsString("preferred_username");
			if (name != null && !name.isBlank()) return name;
		}
		return principal != null ? principal.getName() : "local";
	}
}
