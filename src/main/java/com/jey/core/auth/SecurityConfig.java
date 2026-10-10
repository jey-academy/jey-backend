package com.jey.core.auth;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Configuration(proxyBeanMethods = false)
class SecurityConfig {

	private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

	private static final String[] PUBLIC_PATHS = {
			"/v3/api-docs/**",
			"/swagger-ui/**",
			"/swagger-ui.html",
			"/actuator/health/**"
	};

	// 공개 경로 외에는 인증이 필요하다. 인증이 없으면 401이고, CSRF 토큰 없는 변경 요청은
	// CSRF 필터가 먼저 돌기 때문에 인증 여부와 무관하게 403이다.
	// 401·403은 필터에서 나므로 MVC 예외 처리기로 넘겨 컨트롤러 예외와 같은 Problem Details 형식으로 응답한다.
	// HandlerExceptionResolver 빈이 여러 개라 MVC의 합성 리졸버를 이름으로 지정한다.
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
								delegate(exceptionResolver, request, response, authException, HttpStatus.UNAUTHORIZED))
						.accessDeniedHandler((request, response, accessDeniedException) ->
								delegate(exceptionResolver, request, response, accessDeniedException, HttpStatus.FORBIDDEN)))
				.build();
	}

	// 변환기가 응답을 쓰지 못하면(처리기 안에서 오류가 난 경우 등) 상태가 200으로 남아 인증 실패가 성공처럼 보인다.
	// 그때는 본문이 없더라도 실패 상태로 응답한다.
	static void delegate(HandlerExceptionResolver exceptionResolver, HttpServletRequest request,
			HttpServletResponse response, Exception ex, HttpStatus fallback) throws IOException {
		if (exceptionResolver.resolveException(request, response, null, ex) == null && !response.isCommitted()) {
			log.error("보안 예외를 Problem Details로 변환하지 못했다: {} {}", request.getMethod(), request.getRequestURI(), ex);
			response.sendError(fallback.value());
		}
	}

}
