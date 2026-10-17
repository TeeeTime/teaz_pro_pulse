package pro.teaz.pulse.security;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import pro.teaz.pulse.PulseProperties;

/**
 * Production security: every request needs a valid Keycloak access token
 * (Bearer JWT) issued by the realm configured in application-prod.properties.
 * Managing containers additionally needs the realm role {@value #ADMIN_ROLE};
 * {@code /api/health} is open to any logged-in user, also cross-origin
 * from the other Teaz apps.
 */
@Configuration
@Profile("prod")
public class SecurityConfig {

	public static final String ADMIN_ROLE = "pulse-admin";

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
			.authorizeHttpRequests(auth -> auth
				.requestMatchers(HttpMethod.GET, "/api/health").authenticated()
				.requestMatchers("/api/containers/**").hasRole(ADMIN_ROLE)
				.anyRequest().authenticated())
			.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
			.cors(Customizer.withDefaults())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.csrf(csrf -> csrf.disable());
		return http.build();
	}

	private static JwtAuthenticationConverter jwtAuthenticationConverter() {
		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRolesConverter());
		return converter;
	}

	/** Picked up by {@code .cors()} above. Only the health endpoint is shared. */
	@Bean
	CorsConfigurationSource corsConfigurationSource(PulseProperties properties) {
		CorsConfiguration health = new CorsConfiguration();
		health.setAllowedOrigins(properties.cors().allowedOrigins());
		health.setAllowedMethods(List.of("GET"));
		health.setAllowedHeaders(List.of("Authorization"));

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/health", health);
		return source;
	}
}
