package pro.teaz.pulse.deploy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class DeployerClientTests {

	private static final String BASE = "http://deployer:8080";

	private MockRestServiceServer server;
	private DeployerClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
		server = MockRestServiceServer.bindTo(builder).build();
		client = new DeployerClient(builder.build());
	}

	@Test
	void startsDeployAndPassesUser() {
		server.expect(requestTo(BASE + "/deploys/teaz_pro_portal"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header(DeployerClient.USER_HEADER, "tom"))
				.andRespond(withStatus(HttpStatus.ACCEPTED).contentType(MediaType.APPLICATION_JSON).body("""
						{"project":"teaz_pro_portal","state":"running","startedBy":"tom",
						 "startedAt":"2026-11-10T12:00:00Z","finishedAt":null,"exitCode":null,
						 "log":"$ git pull --ff-only\\n"}
						"""));

		DeployJob job = client.start("teaz_pro_portal", "tom");

		assertThat(job).isEqualTo(new DeployJob("teaz_pro_portal", "running", "tom",
				Instant.parse("2026-11-10T12:00:00Z"), null, null, "$ git pull --ff-only\n"));
		server.verify();
	}

	@Test
	void keepsDeployersRefusal() {
		server.expect(requestTo(BASE + "/deploys/teaz_pro_portal"))
				.andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
						.body("{\"detail\":\"A deploy of teaz_pro_study is still running.\"}"));

		assertThatThrownBy(() -> client.start("teaz_pro_portal", "tom"))
				.isInstanceOfSatisfying(ResponseStatusException.class, e -> {
					assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
					assertThat(e.getReason()).isEqualTo("A deploy of teaz_pro_study is still running.");
				});
	}

	@Test
	void protectedIsAConflictNotForbidden() {
		server.expect(requestTo(BASE + "/deploys/teaz_pro_pulse"))
				.andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON)
						.body("{\"detail\":\"teaz_pro_pulse has protected containers.\"}"));

		assertThatThrownBy(() -> client.start("teaz_pro_pulse", "tom"))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
	}

	@Test
	void noJobYetIsEmpty() {
		server.expect(requestTo(BASE + "/deploys/teaz_pro_portal"))
				.andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
						.body("{\"detail\":\"No deploy.\"}"));

		assertThat(client.job("teaz_pro_portal")).isEmpty();
	}

	@Test
	void listsJobs() {
		server.expect(requestTo(BASE + "/deploys"))
				.andRespond(withSuccess("""
						[{"project":"a","state":"succeeded","startedBy":"tom","startedAt":"2026-11-10T12:00:00Z",
						  "finishedAt":"2026-11-10T12:01:00Z","exitCode":0,"log":""}]
						""", MediaType.APPLICATION_JSON));

		assertThat(client.jobs()).extracting(DeployJob::project, DeployJob::exitCode)
				.containsExactly(org.assertj.core.api.Assertions.tuple("a", 0));
	}

	@Test
	void unreachableIsServiceUnavailable() {
		RestClient unreachable = RestClient.builder().baseUrl("http://127.0.0.1:1").build();

		assertThatThrownBy(() -> new DeployerClient(unreachable).jobs())
				.isInstanceOfSatisfying(ResponseStatusException.class, e -> {
					assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
					assertThat(e.getReason()).isEqualTo("Deployer not available.");
				});
	}
}
