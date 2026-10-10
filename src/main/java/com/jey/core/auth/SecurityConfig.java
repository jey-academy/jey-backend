package com.jey.core.auth;

import java.io.IOException;
import java.time.Clock;
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
import org.springframework.data.redis.serializer.RedisSerializer;
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
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
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

	// 인증은 서버 세션(Redis)이다. 로그인·로그아웃은 LoginService가 직접 처리하므로 폼 로그인은 켜지 않고 기본 로그아웃 필터는 끈다.
	// 401·403은 필터에서 나므로 MVC 예외 처리기로 넘겨 컨트롤러 예외와 같은 Problem Details 형식으로 응답한다.
	// HandlerExceptionResolver 빈이 여러 개라 MVC의 합성 리졸버를 이름으로 지정한다.
	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver,
			SecurityContextRepository securityContextRepository, CsrfTokenRepository csrfTokenRepository,
			CorsConfigurationSource corsConfigurationSource, Clock clock) throws Exception {
		return http
				.cors(cors -> cors.configurationSource(corsConfigurationSource))
				// 토큰을 쿠키(XSRF-TOKEN)로 주고 헤더(X-XSRF-TOKEN)로 받는다. CSRF 검사는 인증 검사보다 먼저 돌기 때문에
				// 토큰 없는 변경 요청은 로그인 여부와 무관하게 403이다.
				.csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokenRepository))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(PUBLIC_PATHS).permitAll()
						.requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
						// 로그인은 인증 없이 열지만 CSRF 토큰은 필요하다.
						// 다른 사이트가 피해자의 브라우저를 공격자 계정으로 로그인시키는 것을 막는다.
						.requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
						.anyRequest().authenticated())
				.securityContext(context -> context.securityContextRepository(securityContextRepository))
				// 로그인 정보를 읽기 전에, 최대 유지 시간이 지난 세션을 먼저 끊는다.
				.addFilterBefore(new SessionMaxLifetimeFilter(clock), SecurityContextHolderFilter.class)
				// 인증 없는 요청을 로그인 후 다시 실행하려고 세션에 저장하는 기능을 끈다.
				// 켜 두면 인증 없는 GET마다 Redis에 세션이 생긴다.
				.requestCache(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable)
				.exceptionHandling(ex -> ex
						.authenticationEntryPoint((request, response, authException) ->
								delegate(exceptionResolver, request, response, authException, HttpStatus.UNAUTHORIZED))
						.accessDeniedHandler((request, response, accessDeniedException) -> {
							// CSRF 거부는 프론트가 토큰을 싣지 못할 때 나타나는 대표 증상이라 흔적을 남긴다.
							if (accessDeniedException instanceof CsrfException) {
								log.warn("CSRF 토큰 거부: {} {} ({})", request.getMethod(), request.getRequestURI(),
										accessDeniedException.getClass().getSimpleName());
							}
							delegate(exceptionResolver, request, response, accessDeniedException, HttpStatus.FORBIDDEN);
						}))
				.build();
	}

	// 로그인한 사용자를 세션에 저장한다. LoginService가 로그인 성공 시 직접 저장할 때도 같은 저장소를 쓴다.
	@Bean
	SecurityContextRepository securityContextRepository() {
		return new HttpSessionSecurityContextRepository();
	}

	// 이미 세션이 있을 때 로그인하면 세션 ID를 바꾼다(세션이 없으면 새로 만들어진다).
	// 공격자가 미리 심어 둔 세션 ID로 로그인 상태를 가로채는 것을 막는다.
	// 이 전략만 쓰므로 로그인·로그아웃 때 CSRF 토큰은 바뀌지 않는다.
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

	// 세션 쿠키 속성을 여기서 직접 정한다. Spring Boot의 기본 구성은 내장 서버에서는 server.servlet.session.cookie.* 설정을,
	// 그 밖의 환경(MockMvc 테스트 등)에서는 서블릿 컨테이너의 기본값을 읽는다. 환경과 무관하게 같은 속성이 붙도록 빈으로 고정한다.
	// 이 빈이 있으면 server.servlet.session.cookie.* 설정은 쓰이지 않는다.
	// JavaScript에서 읽을 수 없고(httpOnly), 다른 사이트에서 온 요청에는 최상위 GET 이동을 빼고 실리지 않는다(SameSite=Lax).
	@Bean
	CookieSerializer sessionCookieSerializer(AuthProperties properties) {
		DefaultCookieSerializer serializer = new DefaultCookieSerializer();
		serializer.setCookieName("SESSION");
		serializer.setUseHttpOnlyCookie(true);
		serializer.setSameSite("Lax");
		serializer.setUseSecureCookie(properties.secureCookies());
		return serializer;
	}

	// Spring Session이 세션 값을 Redis에 넣고 꺼낼 때 쓴다. 빈 이름으로 찾으므로 이름을 바꾸면 안 된다.
	@Bean
	RedisSerializer<Object> springSessionDefaultRedisSerializer() {
		return new TolerantSessionSerializer(getClass().getClassLoader());
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
		if (exceptionResolver.resolveException(request, response, null, ex) == null) {
			log.error("보안 예외를 Problem Details로 변환하지 못했다: {} {}", request.getMethod(), request.getRequestURI(), ex);
			if (!response.isCommitted()) {
				response.sendError(fallback.value());
			}
		}
	}

}
