package com.jey.core.auth;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
class SecurityConfig {

	private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

	private static final String[] PUBLIC_PATHS = {
			"/v3/api-docs/**",
			"/swagger-ui/**",
			"/swagger-ui.html",
			"/actuator/health/**"
	};

	// 인증은 서버 세션(Redis)이다. 로그인·로그아웃은 AuthController가 직접 처리하므로 폼 로그인과 기본 로그아웃은 끈다.
	// 401·403은 필터에서 나므로 MVC 예외 처리기로 넘겨 컨트롤러 예외와 같은 Problem Details 형식으로 응답한다.
	// HandlerExceptionResolver 빈이 여러 개라 MVC의 합성 리졸버를 이름으로 지정한다.
	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver,
			SecurityContextRepository securityContextRepository, CsrfTokenRepository csrfTokenRepository,
			CorsConfigurationSource corsConfigurationSource) throws Exception {
		return http
				.cors(cors -> cors.configurationSource(corsConfigurationSource))
				// 토큰을 쿠키(XSRF-TOKEN)로 주고 헤더(X-XSRF-TOKEN)로 받는다. CSRF 검사는 인증 검사보다 먼저 돌기 때문에
				// 토큰 없는 변경 요청은 로그인 여부와 무관하게 403이다.
				.csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokenRepository))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(PUBLIC_PATHS).permitAll()
						.requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
						.anyRequest().authenticated())
				.securityContext(context -> context.securityContextRepository(securityContextRepository))
				// 인증 없는 요청을 기억해 두려고 세션을 만들지 않게 한다. Redis에 빈 세션이 쌓이는 것을 막는다.
				.requestCache(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable)
				.exceptionHandling(ex -> ex
						.authenticationEntryPoint((request, response, authException) ->
								delegate(exceptionResolver, request, response, authException, HttpStatus.UNAUTHORIZED))
						.accessDeniedHandler((request, response, accessDeniedException) ->
								delegate(exceptionResolver, request, response, accessDeniedException, HttpStatus.FORBIDDEN)))
				.build();
	}

	// 로그인한 사용자를 세션에 저장한다. AuthController가 로그인 성공 시 직접 저장할 때도 같은 저장소를 쓴다.
	@Bean
	SecurityContextRepository securityContextRepository() {
		return new HttpSessionSecurityContextRepository();
	}

	// 로그인에 성공하면 세션 ID를 바꾼다. 공격자가 미리 심어 둔 세션 ID로 로그인 상태를 가로채는 것을 막는다.
	@Bean
	SessionAuthenticationStrategy sessionAuthenticationStrategy() {
		return new ChangeSessionIdAuthenticationStrategy();
	}

	// 프론트가 JavaScript로 읽어 헤더에 실어야 하므로 httpOnly가 아니다.
	@Bean
	CsrfTokenRepository csrfTokenRepository(AuthProperties properties) {
		CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
		repository.setCookieCustomizer(cookie -> {
			cookie.sameSite("Lax");
			cookie.secure(properties.secureCookies());
			if (properties.hasCookieDomain()) {
				cookie.domain(properties.cookieDomain());
			}
		});
		return repository;
	}

	// 세션 쿠키 속성을 여기서 직접 정한다. Spring Boot의 기본 구성은 실행 환경(내장 서버, 테스트)에 따라
	// 다른 곳에서 값을 읽어 오므로, 환경과 무관하게 같은 속성이 붙도록 빈으로 고정한다.
	// JavaScript에서 읽을 수 없고(httpOnly), 다른 사이트에서 온 변경 요청에는 실리지 않는다(SameSite=Lax).
	@Bean
	CookieSerializer sessionCookieSerializer(AuthProperties properties) {
		DefaultCookieSerializer serializer = new DefaultCookieSerializer();
		serializer.setCookieName("SESSION");
		serializer.setUseHttpOnlyCookie(true);
		serializer.setSameSite("Lax");
		serializer.setUseSecureCookie(properties.secureCookies());
		return serializer;
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource(AuthProperties properties) {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(properties.allowedOrigins());
		configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		configuration.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN", "Idempotency-Key"));
		// 세션 쿠키를 실은 요청을 받아야 한다. 이 경우 허용 출처에 *를 쓸 수 없어 목록으로 받는다.
		configuration.setAllowCredentials(true);
		configuration.setMaxAge(Duration.ofHours(1));
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", configuration);
		return source;
	}

	@Bean
	AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
		return configuration.getAuthenticationManager();
	}

	// 해시 앞에 알고리즘 이름({bcrypt})을 붙여 저장한다. 나중에 알고리즘을 바꿔도 기존 해시를 그대로 검증할 수 있다.
	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
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
