package pro.teaz.pulse.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakRealmRolesConverterTests {

	private final KeycloakRealmRolesConverter converter = new KeycloakRealmRolesConverter();

	@Test
	void mapsRealmRolesToRoleAuthorities() {
		Jwt jwt = token(Map.of("realm_access", Map.of("roles", List.of("pulse-admin", "offline_access"))));

		assertThat(converter.convert(jwt)).extracting(GrantedAuthority::getAuthority)
				.containsExactly("ROLE_pulse-admin", "ROLE_offline_access");
	}

	@Test
	void noRealmAccessMeansNoRoles() {
		assertThat(converter.convert(token(Map.of("sub", "someone")))).isEmpty();
	}

	private static Jwt token(Map<String, Object> claims) {
		Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "none");
		claims.forEach(builder::claim);
		return builder.build();
	}
}
