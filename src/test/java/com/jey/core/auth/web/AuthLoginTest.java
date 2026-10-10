package com.jey.core.auth.web;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import com.jey.TestCsrf;
import com.jey.TestcontainersConfiguration;
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

	@Autowired
	MockMvc mockMvc;

	@Autowired
	UserRepository users;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	StringRedisTemplate redis;

	private User staff;

	private User admin;

	private User disabled;

	@BeforeEach
	void createAccounts() {
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

	@Test
	void 로그인한_세션으로_내_정보를_조회한다() throws Exception {
		Cookie session = loginAndGetSession(admin.getLoginId());

		mockMvc.perform(get("/api/v1/auth/me").cookie(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(admin.getId()))
				.andExpect(jsonPath("$.name").value("박원장"))
				.andExpect(jsonPath("$.role").value("ADMIN"))
				.andExpect(jsonPath("$.campusId").doesNotExist());
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

		assertThat(wrongPassword).contains("\"code\":\"AUTH_INVALID_CREDENTIALS\"")
				.contains("아이디 또는 비밀번호가 올바르지 않습니다.");
		assertThat(unknownId).isEqualTo(wrongPassword);
		assertThat(disabledAccount).isEqualTo(wrongPassword);
	}

	@Test
	void 로그인에_실패하면_세션을_만들지_않는다() throws Exception {
		login(staff.getLoginId(), "wrong-password")
				.andExpect(status().isUnauthorized())
				.andExpect(cookie().doesNotExist(SESSION));
	}

	@Test
	void 아이디나_비밀번호가_비면_검증_오류다() throws Exception {
		login("", "")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
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

		mockMvc.perform(post("/api/v1/auth/logout").cookie(session).with(csrfToken(mockMvc)))
				.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isUnauthorized());
		assertThat(redis.hasKey(redisKey(session))).isFalse();
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
	@Test
	void 세션_만료_시간은_역할마다_다르다() throws Exception {
		Cookie staffSession = loginAndGetSession(staff.getLoginId());
		Cookie adminSession = loginAndGetSession(admin.getLoginId());

		assertThat(redis.getExpire(redisKey(staffSession)))
				.isBetween(Duration.ofHours(12).minusMinutes(1).toSeconds(), Duration.ofHours(12).plusMinutes(6).toSeconds());
		assertThat(redis.getExpire(redisKey(adminSession)))
				.isBetween(Duration.ofHours(2).minusMinutes(1).toSeconds(), Duration.ofHours(2).plusMinutes(6).toSeconds());
	}

	@Test
	void 비밀번호와_해시는_세션과_로그에_남지_않는다(CapturedOutput output) throws Exception {
		Cookie session = loginAndGetSession(staff.getLoginId());
		login(staff.getLoginId(), "wrong-password-for-log-check");

		String stored = String.valueOf(redis.opsForHash().entries(redisKey(session)));
		assertThat(stored).doesNotContain(PASSWORD).doesNotContain("{bcrypt}");
		assertThat(output).doesNotContain(PASSWORD).doesNotContain("wrong-password-for-log-check");
	}

	private User newUser(String prefix, String name, UserRole role, Long campusId) {
		String loginId = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
		return User.create(loginId, passwordEncoder.encode(PASSWORD), name, role, campusId);
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
		MvcResult result = login(loginId, password).andExpect(status().isUnauthorized()).andReturn();
		return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
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
