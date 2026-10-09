package com.jey.core.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

@Configuration(proxyBeanMethods = false)
class SecurityConfig {

	private static final String[] PUBLIC_PATHS = {
			"/v3/api-docs/**",
			"/swagger-ui/**",
			"/swagger-ui.html",
			"/actuator/health/**"
	};

	// JWT 인증은 아직 없다. 공개 경로 외에는 모두 401.
	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		return http
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(PUBLIC_PATHS).permitAll()
						.anyRequest().authenticated())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
				.build();
	}

}
