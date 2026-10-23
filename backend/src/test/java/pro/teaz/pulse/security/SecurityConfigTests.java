package pro.teaz.pulse.security;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import pro.teaz.pulse.docker.DockerClient;
import pro.teaz.pulse.docker.DockerContainer;
import pro.teaz.pulse.metrics.HostProbe;
import pro.teaz.pulse.metrics.HostSample;
import pro.teaz.pulse.metrics.MetricsSampler;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class SecurityConfigTests {

	private static final SimpleGrantedAuthority ADMIN = new SimpleGrantedAuthority("ROLE_" + SecurityConfig.ADMIN_ROLE);

	@Autowired
	MockMvc mvc;

	// Prod would talk to the socket proxy, which doesn't exist in tests.
	@MockitoBean
	DockerClient docker;

	// Prod would read the VPS's /proc.
	@MockitoBean
	HostProbe host;

	@Autowired
	MetricsSampler sampler;

	@BeforeEach
	void containers() {
		when(docker.listManagedContainers()).thenReturn(List.of(new DockerContainer(
				"abc", "portal-backend-1", "Portal", "backend", "img", "running", null, null, null, 0, false)));
	}

	@Test
	void rejectsRequestWithoutToken() throws Exception {
		mvc.perform(get("/")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/health")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/containers")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/metrics")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/load")).andExpect(status().isUnauthorized());
	}

	@Test
	void acceptsRequestWithToken() throws Exception {
		// No endpoint at /, so an authenticated request gets past security and hits 404.
		mvc.perform(get("/").with(jwt())).andExpect(status().isNotFound());
	}

	@Test
	void healthIsOpenToAnyLoggedInUser() throws Exception {
		mvc.perform(get("/api/health").with(jwt())).andExpect(status().isOk());
	}

	@Test
	void loadIsOpenToAnyLoggedInUser() throws Exception {
		when(host.sample()).thenReturn(new HostSample(10, 100, 2, new double[] {0, 0, 0}, 1000, 500, 2000, 1000));
		sampler.sample();

		mvc.perform(get("/api/load").with(jwt())).andExpect(status().isOk());
	}

	@Test
	void containersNeedAdminRole() throws Exception {
		mvc.perform(get("/api/containers").with(jwt())).andExpect(status().isForbidden());
		mvc.perform(post("/api/containers/abc/stop").with(jwt())).andExpect(status().isForbidden());

		mvc.perform(get("/api/containers").with(jwt().authorities(ADMIN))).andExpect(status().isOk());
		mvc.perform(post("/api/containers/abc/stop").with(jwt().authorities(ADMIN))).andExpect(status().isNoContent());
	}

	@Test
	void metricsNeedAdminRole() throws Exception {
		when(host.sample()).thenReturn(new HostSample(10, 100, 2, new double[] {0, 0, 0}, 1000, 500, 2000, 1000));
		sampler.sample();

		mvc.perform(get("/api/metrics").with(jwt())).andExpect(status().isForbidden());
		mvc.perform(get("/api/metrics").with(jwt().authorities(ADMIN))).andExpect(status().isOk());
	}

	@Test
	void onlyHealthAndLoadAreSharedCrossOrigin() throws Exception {
		mvc.perform(options("/api/health")
				.header("Origin", "https://portal.teaz.pro")
				.header("Access-Control-Request-Method", "GET")
				.header("Access-Control-Request-Headers", "Authorization"))
			.andExpect(status().isOk())
			.andExpect(header().string("Access-Control-Allow-Origin", "https://portal.teaz.pro"));
		mvc.perform(options("/api/load")
				.header("Origin", "https://portal.teaz.pro")
				.header("Access-Control-Request-Method", "GET")
				.header("Access-Control-Request-Headers", "Authorization"))
			.andExpect(status().isOk())
			.andExpect(header().string("Access-Control-Allow-Origin", "https://portal.teaz.pro"));

		mvc.perform(options("/api/containers")
				.header("Origin", "https://portal.teaz.pro")
				.header("Access-Control-Request-Method", "GET"))
			.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
		mvc.perform(options("/api/metrics")
				.header("Origin", "https://portal.teaz.pro")
				.header("Access-Control-Request-Method", "GET"))
			.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
	}
}
