package pro.teaz.pulse.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class DockerEngineClientTests {

	private static final String BASE = "http://docker-proxy:2375/v1.44";

	private MockRestServiceServer server;
	private DockerEngineClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
		server = MockRestServiceServer.bindTo(builder).build();
		client = new DockerEngineClient(builder.build());
	}

	@Test
	void listsAndInspectsLabeledContainers() {
		server.expect(requestTo(allOf(
						startsWith(BASE + "/containers/json?all=true&filters="),
						containsString("pro.teaz.pulse.app"))))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess("""
						[{"Id": "aaa", "Names": ["/portal-backend-1"], "State": "running"},
						 {"Id": "bbb", "Names": ["/traefik"], "State": "exited"}]
						""", MediaType.APPLICATION_JSON));
		server.expect(requestTo(BASE + "/containers/aaa/json"))
				.andRespond(withSuccess("""
						{"Id": "aaa", "Name": "/portal-backend-1", "RestartCount": 2,
						 "State": {"Status": "running", "Running": true,
						           "StartedAt": "2026-10-17T08:15:30.123456789Z",
						           "FinishedAt": "0001-01-01T00:00:00Z",
						           "Health": {"Status": "healthy", "FailingStreak": 0}},
						 "Config": {"Image": "teaz_pro_portal-backend",
						            "Labels": {"pro.teaz.pulse.app": "Teaz Portal",
						                       "com.docker.compose.service": "backend"}}}
						""", MediaType.APPLICATION_JSON));
		server.expect(requestTo(BASE + "/containers/bbb/json"))
				.andRespond(withSuccess("""
						{"Id": "bbb", "Name": "/traefik", "RestartCount": 0,
						 "State": {"Status": "exited", "Running": false,
						           "StartedAt": "2026-10-01T10:00:00Z",
						           "FinishedAt": "2026-10-17T09:00:00Z"},
						 "Config": {"Image": "traefik:v3.4",
						            "Labels": {"pro.teaz.pulse.app": "Traefik",
						                       "pro.teaz.pulse.protected": "true"}}}
						""", MediaType.APPLICATION_JSON));

		List<DockerContainer> containers = client.listManagedContainers();

		assertThat(containers).containsExactly(
				new DockerContainer("aaa", "portal-backend-1", "Teaz Portal", "backend", "teaz_pro_portal-backend",
						"running", "healthy", Instant.parse("2026-10-17T08:15:30.123456789Z"), null, 2, false),
				// no compose label: service falls back to the container name; no healthcheck: health null
				new DockerContainer("bbb", "traefik", "Traefik", "traefik", "traefik:v3.4",
						"exited", null, Instant.parse("2026-10-01T10:00:00Z"), Instant.parse("2026-10-17T09:00:00Z"),
						0, true));
		server.verify();
	}

	@Test
	void postsActions() {
		server.expect(requestTo(BASE + "/containers/aaa/start")).andExpect(method(HttpMethod.POST))
				.andRespond(withStatus(HttpStatus.NO_CONTENT));
		server.expect(requestTo(BASE + "/containers/aaa/stop?t=10")).andExpect(method(HttpMethod.POST))
				.andRespond(withStatus(HttpStatus.NO_CONTENT));
		// 304: already restarted/stopped, not an error
		server.expect(requestTo(BASE + "/containers/aaa/restart?t=10")).andExpect(method(HttpMethod.POST))
				.andRespond(withStatus(HttpStatus.NOT_MODIFIED));

		client.start("aaa");
		client.stop("aaa");
		client.restart("aaa");
		server.verify();
	}
}
