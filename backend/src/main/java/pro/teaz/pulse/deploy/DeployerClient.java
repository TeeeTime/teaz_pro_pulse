package pro.teaz.pulse.deploy;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * The deployer sidecar's API over HTTP (deployer/Deployer.java). Its refusals
 * (unknown project, protected, already running) come back with their status
 * and the deployer's reason, so the UI can show them.
 */
public class DeployerClient implements Deployer {

	/** The deployer's header for who started a deploy. */
	static final String USER_HEADER = "X-Pulse-User";

	private final RestClient restClient;

	public DeployerClient(RestClient restClient) {
		this.restClient = restClient;
	}

	@Override
	public DeployJob start(String project, String user) {
		return call(() -> restClient.post()
				.uri("/deploys/{project}", project)
				.header(USER_HEADER, user)
				.exchange((request, response) -> {
					if (response.getStatusCode().isError()) throw failure(response);
					return response.bodyTo(DeployJob.class);
				}));
	}

	@Override
	public Optional<DeployJob> job(String project) {
		return call(() -> restClient.get()
				.uri("/deploys/{project}", project)
				.exchange((request, response) -> {
					if (response.getStatusCode().value() == 404) return Optional.<DeployJob>empty();
					if (response.getStatusCode().isError()) throw failure(response);
					return Optional.ofNullable(response.bodyTo(DeployJob.class));
				}));
	}

	@Override
	public List<DeployJob> jobs() {
		return call(() -> restClient.get()
				.uri("/deploys")
				.exchange((request, response) -> {
					if (response.getStatusCode().isError()) throw failure(response);
					List<DeployJob> jobs = response.bodyTo(new ParameterizedTypeReference<List<DeployJob>>() {
					});
					return jobs != null ? jobs : List.<DeployJob>of();
				}));
	}

	/** Refusals keep their status and reason; anything else from the deployer is a bad gateway. */
	private static ResponseStatusException failure(ConvertibleClientHttpResponse response) {
		HttpStatusCode status;
		String detail;
		try {
			status = response.getStatusCode();
			Problem problem = response.bodyTo(Problem.class);
			detail = problem != null && problem.detail() != null ? problem.detail() : "Deployer answered " + status.value() + ".";
		} catch (Exception e) {
			return new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Deployer answered with an error.");
		}
		return switch (status.value()) {
			case 400, 404, 409 -> new ResponseStatusException(status, detail);
			// Its 403 means protected/outside the root; keep 403 out of the UI's "needs pulse-admin" handling.
			case 403 -> new ResponseStatusException(HttpStatus.CONFLICT, detail);
			default -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, detail);
		};
	}

	/** Deployer down or unreachable: 503 with its own message, not DockerErrorHandler's. */
	private static <T> T call(Supplier<T> request) {
		try {
			return request.get();
		} catch (RestClientException e) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Deployer not available.", e);
		}
	}

	record Problem(String detail) {
	}
}
