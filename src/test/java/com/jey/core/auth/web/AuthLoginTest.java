package com.jey.core.auth.web;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import com.jey.TestcontainersConfiguration;
import com.jey.core.auth.AccountService;
import com.jey.core.auth.api.UserRole;
import com.jey.core.auth.domain.User;
import com.jey.core.auth.domain.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import static com.jey.TestCsrf.csrfToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class AuthLoginTest {

	private static final String SESSION = "SESSION";

	private static final String PASSWORD = "correct-horse-battery-staple";

	private static final String SECURITY_CONTEXT_FIELD = "sessionAttr:SPRING_SECURITY_CONTEXT";

	// 비밀번호 해시는 계산이 느리다. 테스트마다 다시 만들지 않고 한 번 만든 것을 같이 쓴다.
	private static String passwordHash;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	UserRepository users;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	AccountService accountService;

	@Autowired
	Clock clock;

	private User staff;

	private User admin;

	private User disabled;

	@BeforeEach
	void createAccounts() {
		if (passwordHash == null) {
			passwordHash = passwordEncoder.encode(PASSWORD);
		}
		staff = users.saveAndFlush(newUser("staff", "김직원", UserRole.STAFF, 1L));
		admin = users.saveAndFlush(newUser("admin", "박원장", UserRole.ADMIN, null));
		User toDisable = newUser("left", "퇴사자", UserRole.STAFF, 1L);
		toDisable.disable();
		disabled = users.saveAndFlush(toDisable);
	}

	@Test
	void 아이디와_비밀번호가_맞으면_로그인되고_세션_쿠키를_받는다() throws Exception {
		login(staff.getLoginId(), PASSWORD)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(staff.getId()))
				.andExpect(jsonPath("$.loginId").value(staff.getLoginId()))
				.andExpect(jsonPath("$.name").value("김직원"))
				.andExpect(jsonPath("$.role").value("STAFF"))
				.andExpect(jsonPath("$.campusId").value(1))
				.andExpect(cookie().exists(SESSION))
				.andExpect(cookie().httpOnly(SESSION, true))
				.andExpect(cookie().sameSite(SESSION, "Lax"))
				.andExpect(content().string(not(containsString("password"))))
				.andExpect(content().string(not(containsString(PASSWORD))));
	}

	// DB는 대소문자를 구분하지 않는다. 저장할 때와 찾을 때 모두 소문자로 맞춰서 Java와 DB의 판단이 어긋나지 않게 한다.
	@Test
	void 아이디는_대소문자와_앞뒤_공백을_구분하지_않는다() throws Exception {
		login("  " + staff.getLoginId().toUpperCase() + " ", PASSWORD)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.loginId").value(staff.getLoginId()));
	}

	@Test
	void 로그인한_세션으로_내_정보를_조회한다() throws Exception {
		Cookie session = loginAndGetSession(admin.getLoginId());

		mockMvc.perform(get("/api/v1/auth/me").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(admin.getId()))
				.andExpect(jsonPath("$.name").value("박원장"))
				.andExpect(jsonPath("$.role").value("ADMIN"))
				// 지점이 없으면 필드를 빼지 않고 null로 내보낸다. 응답의 모양이 항상 같다.
				.andExpect(jsonPath("$.campusId").value(nullValue()));
	}

	@Test
	void 세션이_없으면_내_정보를_조회할_수_없다() throws Exception {
		mockMvc.perform(get("/api/v1/auth/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("COMMON_UNAUTHENTICATED"));
	}

	// 응답이 다르면 어떤 아이디가 존재하는지, 어떤 계정이 비활성인지 알아낼 수 있다.
	@Test
	void 로그인_실패는_이유와_무관하게_같은_응답이다() throws Exception {
		String wrongPassword = failedLoginBody(staff.getLoginId(), "wrong-password");
		String unknownId = failedLoginBody("no-such-user", PASSWORD);
		String disabledAccount = failedLoginBody(disabled.getLoginId(), PASSWORD);
		String disabledWrongPassword = failedLoginBody(disabled.getLoginId(), "wrong-password");

		assertThat(wrongPassword).contains("\"code\":\"AUTH_INVALID_CREDENTIALS\"")
				.contains("아이디 또는 비밀번호가 올바르지 않습니다.");
		assertThat(unknownId).isEqualTo(wrongPassword);
		assertThat(disabledAccount).isEqualTo(wrongPassword);
		assertThat(disabledWrongPassword).isEqualTo(wrongPassword);
	}

	// 비활성 여부를 비밀번호보다 먼저 확인하면 비활성 계정만 해시 계산 없이 빨리 실패한다.
	// 그 시간 차이로 "존재하지만 비활성인 아이디"를 가려낼 수 있으므로, 비밀번호가 맞았을 때만 비활성 여부를 본다.
	@Test
	void 비활성_여부는_비밀번호가_맞은_뒤에_확인한다(CapturedOutput output) throws Exception {
		login(disabled.getLoginId(), "wrong-password").andExpect(status().isUnauthorized());
		assertThat(output).contains("로그인 실패: BadCredentialsException").doesNotContain("비활성 계정");

		login(disabled.getLoginId(), PASSWORD).andExpect(status().isUnauthorized());
		assertThat(output).contains("로그인 실패: 비활성 계정 userId=" + disabled.getId());
	}

	@Test
	void 로그인에_실패하면_세션을_만들지_않는다() throws Exception {
		login(staff.getLoginId(), "wrong-password")
				.andExpect(status().isUnauthorized())
				.andExpect(cookie().doesNotExist(SESSION));
	}

	@Test
	void 아이디나_비밀번호가_비거나_너무_길면_검증_오류다() throws Exception {
		login("", "").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
		login("a".repeat(51), PASSWORD).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
		login(staff.getLoginId(), "p".repeat(101)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
				// 거부된 입력값(비밀번호)을 응답에 되돌려 주지 않는다.
				.andExpect(content().string(not(containsString("pppppppppp"))));
	}

	@Test
	void CSRF_토큰_없이는_로그인할_수_없다() throws Exception {
		mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
						.content(loginJson(staff.getLoginId(), PASSWORD)))
				.andExpect(status().isForbidden());
	}

	// 공격자가 미리 알고 있는 세션 ID로 로그인 상태를 가로채지 못하게, 로그인하면 세션 ID가 바뀌어야 한다.
	@Test
	void 이미_세션이_있는_상태에서_로그인하면_세션_ID가_바뀌고_이전_ID는_쓸_수_없다() throws Exception {
		Cookie before = loginAndGetSession(staff.getLoginId());

		Cookie after = login(admin.getLoginId(), PASSWORD, before).andExpect(status().isOk())
				.andReturn().getResponse().getCookie(SESSION);

		assertThat(after).isNotNull();
		assertThat(after.getValue()).isNotEqualTo(before.getValue());
		mockMvc.perform(get("/api/v1/auth/me").cookie(before)).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/auth/me").cookie(after))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("ADMIN"));
	}

	@Test
	void 로그아웃하면_그_세션으로는_더_접근할_수_없다() throws Exception {
		Cookie session = loginAndGetSession(staff.getLoginId());
		assertThat(redis.hasKey(redisKey(session))).isTrue();

		mockMvc.perform(post("/api/v1/auth/logout").cookie(session).with(csrfToken(mockMvc)))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isUnauthorized());
		// Spring Session은 지운 세션의 Redis 키를 바로 없애지 않고 만료된 상태로 잠깐 남겨 둔다. 곧 사라지는지만 확인한다.
		assertThat(redis.getExpire(redisKey(session))).isLessThanOrEqualTo(Duration.ofMinutes(6).toSeconds());
	}

	// 다른 사이트가 사용자를 강제로 로그아웃시키지 못하게 한다.
	@Test
	void CSRF_토큰_없이는_로그아웃할_수_없다() throws Exception {
		Cookie session = loginAndGetSession(staff.getLoginId());

		mockMvc.perform(post("/api/v1/auth/logout").cookie(session)).andExpect(status().isForbidden());

		mockMvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isOk());
	}

	@Test
	void 로그인하지_않은_채_로그아웃하면_401이다() throws Exception {
		mockMvc.perform(post("/api/v1/auth/logout").with(csrfToken(mockMvc)))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void 세션은_Redis에_저장된다() throws Exception {
		Cookie session = loginAndGetSession(staff.getLoginId());

		assertThat(redis.hasKey(redisKey(session))).isTrue();
	}

	// 데스크 직원은 화면을 하루 종일 켜 두고, 관리자는 권한이 커서 짧게 둔다.
	// Spring Session은 Redis 키를 세션 만료 시간보다 5분 더 오래 둔다. 그래서 위쪽으로 여유를 준다.
	@Test
	void 세션_만료_시간은_역할마다_다르다() throws Exception {
		Cookie staffSession = loginAndGetSession(staff.getLoginId());
		Cookie adminSession = loginAndGetSession(admin.getLoginId());

		assertThat(redis.getExpire(redisKey(staffSession)))
				.isBetween(Duration.ofHours(12).minusMinutes(1).toSeconds(), Duration.ofHours(12).plusMinutes(6).toSeconds());
		assertThat(redis.getExpire(redisKey(adminSession)))
				.isBetween(Duration.ofHours(2).minusMinutes(1).toSeconds(), Duration.ofHours(2).plusMinutes(6).toSeconds());
	}

	// 계속 쓰면 세션 만료가 끝없이 연장되므로, 로그인 시점부터의 상한을 세션에 적어 둔다(SessionMaxLifetimeFilter가 확인한다).
	@Test
	void 로그인하면_세션에_최대_유지_시각이_기록된다() throws Exception {
		long before = clock.millis();

		Cookie session = loginAndGetSession(staff.getLoginId());

		assertThat(redis.opsForHash().hasKey(redisKey(session), "sessionAttr:jey.auth.sessionExpiresAt")).isTrue();
		// 값 자체는 직렬화돼 있어 필터 단위 테스트에서 확인하고, 여기서는 로그인 직후에는 끊기지 않는 것만 본다.
		mockMvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isOk());
		assertThat(clock.millis()).isGreaterThanOrEqualTo(before);
	}

	// 세션을 고른 가장 큰 이유다. 퇴사한 직원이나 계약이 끝난 연계 학원의 계정은 로그인돼 있더라도 바로 막혀야 한다.
	@Test
	void 계정을_차단하면_로그인된_세션이_모두_끊기고_다시_로그인할_수_없다() throws Exception {
		Cookie first = loginAndGetSession(staff.getLoginId());
		Cookie second = loginAndGetSession(staff.getLoginId());
		Cookie other = loginAndGetSession(admin.getLoginId());
		mockMvc.perform(get("/api/v1/auth/me").cookie(first)).andExpect(status().isOk());

		accountService.disable(staff.getId());

		mockMvc.perform(get("/api/v1/auth/me").cookie(first)).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/auth/me").cookie(second)).andExpect(status().isUnauthorized());
		login(staff.getLoginId(), PASSWORD).andExpect(status().isUnauthorized());
		// 다른 계정의 세션은 그대로다.
		mockMvc.perform(get("/api/v1/auth/me").cookie(other)).andExpect(status().isOk());
	}

	// 배포로 세션에 넣는 타입이 바뀌면 예전 세션을 읽지 못한다. 그때 500이 나면 로그인 요청도 막혀서 사용자가 빠져나올 수 없다.
	@Test
	void 읽을_수_없는_세션은_로그인하지_않은_것으로_취급하고_다시_로그인할_수_있다(CapturedOutput output) throws Exception {
		Cookie session = loginAndGetSession(staff.getLoginId());
		assertThat(redis.opsForHash().hasKey(redisKey(session), SECURITY_CONTEXT_FIELD)).isTrue();
		corruptSecurityContext(session);

		mockMvc.perform(get("/api/v1/auth/me").cookie(session))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("COMMON_UNAUTHENTICATED"));
		assertThat(output).contains("세션 값을 읽지 못해 없는 것으로 취급한다");

		Cookie relogin = login(staff.getLoginId(), PASSWORD, session).andExpect(status().isOk())
				.andReturn().getResponse().getCookie(SESSION);
		mockMvc.perform(get("/api/v1/auth/me").cookie(relogin)).andExpect(status().isOk());
	}

	@Test
	void 비밀번호와_해시는_세션과_로그에_남지_않는다(CapturedOutput output) throws Exception {
		Cookie session = loginAndGetSession(staff.getLoginId());
		login(staff.getLoginId(), "wrong-password-for-log-check");

		String stored = String.valueOf(redis.opsForHash().entries(redisKey(session)));
		// 세션 내용을 제대로 읽었는지부터 확인한다. 빈 값을 읽으면 아래 검증이 헛돈다.
		assertThat(stored).contains(staff.getLoginId());
		assertThat(stored).doesNotContain(PASSWORD).doesNotContain(passwordHash).doesNotContain("{bcrypt}");
		// 로그도 마찬가지로, 실패 로그가 실제로 찍힌 것을 확인한 뒤에 본다.
		assertThat(output).contains("로그인 실패: BadCredentialsException");
		assertThat(output).doesNotContain(PASSWORD).doesNotContain("wrong-password-for-log-check")
				.doesNotContain(passwordHash);
	}

	// 아이디 칸에 비밀번호를 잘못 넣는 일이 흔하다.
	@Test
	void 로그인_실패_로그에_입력한_아이디를_남기지_않는다(CapturedOutput output) throws Exception {
		login("typed-my-password-here", "whatever").andExpect(status().isUnauthorized());

		assertThat(output).contains("로그인 실패:").doesNotContain("typed-my-password-here");
	}

	@Test
	void 로그인과_로그아웃은_계정_ID로_로그에_남는다(CapturedOutput output) throws Exception {
		Cookie session = loginAndGetSession(staff.getLoginId());
		mockMvc.perform(post("/api/v1/auth/logout").cookie(session).with(csrfToken(mockMvc)))
				.andExpect(status().isNoContent());

		List<String> authLogLines = output.getOut().lines()
				.filter(line -> line.contains("로그인: userId=") || line.contains("로그아웃: userId="))
				.toList();
		assertThat(authLogLines).anyMatch(line -> line.contains("로그인: userId=" + staff.getId() + " role=STAFF"))
				.anyMatch(line -> line.contains("로그아웃: userId=" + staff.getId()))
				// 로그에는 계정 ID만 남기고 아이디와 실명은 남기지 않는다.
				.noneMatch(line -> line.contains(staff.getLoginId()) || line.contains("김직원"));
	}

	private User newUser(String prefix, String name, UserRole role, Long campusId) {
		String loginId = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
		return User.create(loginId, passwordHash, name, role, campusId);
	}

	private ResultActions login(String loginId, String password, Cookie... cookies) throws Exception {
		var request = post("/api/v1/auth/login").with(csrfToken(mockMvc))
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginJson(loginId, password));
		if (cookies.length > 0) {
			request.cookie(cookies);
		}
		return mockMvc.perform(request);
	}

	private Cookie loginAndGetSession(String loginId) throws Exception {
		Cookie session = login(loginId, PASSWORD).andExpect(status().isOk())
				.andReturn().getResponse().getCookie(SESSION);
		assertThat(session).as("로그인 응답의 세션 쿠키").isNotNull();
		return session;
	}

	private String failedLoginBody(String loginId, String password) throws Exception {
		MvcResult result = login(loginId, password)
				.andExpect(status().isUnauthorized())
				.andExpect(cookie().doesNotExist(SESSION))
				.andReturn();
		return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	// Redis에 저장된 로그인 정보를 Java 객체로 읽을 수 없는 바이트로 바꾼다.
	private void corruptSecurityContext(Cookie session) {
		redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
			connection.hashCommands().hSet(redisKey(session).getBytes(StandardCharsets.UTF_8),
					SECURITY_CONTEXT_FIELD.getBytes(StandardCharsets.UTF_8),
					"not-a-java-object".getBytes(StandardCharsets.UTF_8));
			return null;
		});
	}

	private static String loginJson(String loginId, String password) {
		return "{\"loginId\":\"" + loginId + "\",\"password\":\"" + password + "\"}";
	}

	// 세션 쿠키 값은 세션 ID를 Base64로 감싼 것이다.
	private static String redisKey(Cookie session) {
		String sessionId = new String(Base64.getDecoder().decode(session.getValue()), StandardCharsets.UTF_8);
		return "spring:session:sessions:" + sessionId;
	}

}
