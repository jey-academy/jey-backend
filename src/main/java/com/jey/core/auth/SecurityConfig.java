package com.jey.core.auth;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Configuration(proxyBeanMethods = false)
class SecurityConfig {

	private static final String[] PUBLIC_PATHS = {
			"/v3/api-docs/**",
			"/swagger-ui/**",
			"/swagger-ui.html",
			"/actuator/health/**"
	};

	// JWT 인증은 아직 없다. 공개 경로 외에는 모두 401.
	// 401·403은 필터에서 나므로 MVC 예외 처리기로 넘겨 컨트롤러 예외와 같은 Problem Details 형식으로 응답한다.
	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) throws Exception {
		return http
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(PUBLIC_PATHS).permitAll()
						.anyRequest().authenticated())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.exceptionHandling(ex -> ex
						.authenticationEntryPoint((request, response, authException) ->
								exceptionResolver.resolveException(request, response, null, authException))
						.accessDeniedHandler((request, response, accessDeniedException) ->
								exceptionResolver.resolveException(request, response, null, accessDeniedException)))
				.build();
	}

}
