package com.jey.core.auth.web;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import com.jey.TestcontainersConfiguration;
import com.jey.core.auth.api.UserRole;
import com.jey.core.auth.domain.User;
import com.jey.core.auth.domain.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import static com.jey.TestCsrf.csrfToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 요청마다 세션의 계정이 아직 유효한지 본다 (ADR-0031).
// 계정을 바꾼 쪽이 세션을 끊지 못한 상황을 만든다: 세션은 그대로 두고 DB의 계정만 고친다. 그 세션의 다음 요청은 막혀야 한다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SessionAccountCheckTest {

	private static final String SESSION = "SESSION";

	private static final String PASSWORD = "first-password-123";

	// 비밀번호 해시는 계산이 느리다. 테스트마다 다시 만들지 않는다.
	private static String passwordHash;

	private static String otherPasswordHash;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	UserRepository users;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	SecurityFilterChain securityFilterChain;

	@BeforeEach
	void setUp() {
		// 앞에서 돈 테스트가 이 스레드에 로그인 정보를 남겼으면 MockMvc가 그것을 모든 요청의 로그인 정보로 쓴다.
		TestSecurityContextHolder.clearContext();
		redis.delete(redis.keys("jey:auth:login-attempts:*"));
		if (passwordHash == null) {
			passwordHash = passwordEncoder.encode(PASSWORD);
			otherPasswordHash = passwordEncoder.encode("second-password-456");
		}
	}

	@Test
	void 바뀐_것이_없는_계정의_세션은_그대로_일한다() throws Exception {
		User staff = saved(UserRole.STAFF, 1L);
		Cookie session = login(staff);

		mockMvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isOk());
	}

	@Test
	void 차단된_계정의_세션은_끊지_못했더라도_다음_요청에서_막힌다() throws Exception {
		User staff = saved(UserRole.STAFF, 1L);
		Cookie session = login(staff);

		change(staff, User::disable);

		expectLoggedOut(session);
	}

	@Test
	void 역할이_바뀐_계정의_옛_세션은_막힌다() throws Exception {
		User admin = saved(UserRole.ADMIN, null);
		Cookie session = login(admin);

		change(admin, user -> user.changeAffiliation(UserRole.STAFF, 1L));

		expectLoggedOut(session);
	}

	@Test
	void 지점이_바뀐_계정의_옛_세션은_막힌다() throws Exception {
		User staff = saved(UserRole.STAFF, 1L);
		Cookie session = login(staff);

		change(staff, user -> user.changeAffiliation(UserRole.STAFF, 2L));

		expectLoggedOut(session);
	}

	@Test
	void 비밀번호가_바뀐_계정의_옛_세션은_막힌다() throws Exception {
		User staff = saved(UserRole.STAFF, 1L);
		Cookie session = login(staff);

		change(staff, user -> user.changePassword(otherPasswordHash));

		expectLoggedOut(session);
	}

	@Test
	void 이름만_바뀐_계정의_세션은_그대로_일한다() throws Exception {
		User staff = saved(UserRole.STAFF, 1L);
		Cookie session = login(staff);

		change(staff, user -> user.rename("바뀐 이름"));

		mockMvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isOk());
	}

	// 차단할 때 세션을 끊지 못했고, 그 뒤에 차단을 풀었다. 차단 전의 세션이 다시 살아나면 안 된다.
	@Test
	void 차단했다_푼_계정의_옛_세션은_살아나지_않고_다시_로그인하면_된다() throws Exception {
		User staff = saved(UserRole.STAFF, 1L);
		Cookie session = login(staff);

		change(staff, User::disable);
		change(staff, User::enable);

		expectLoggedOut(session);
		Cookie again = login(staff);
		mockMvc.perform(get("/api/v1/auth/me").cookie(again)).andExpect(status().isOk());
	}

	// 만료된 세션을 걸러낸 뒤에 계정을 읽고, 그 뒤에 로그인 정보를 읽는다. 순서가 바뀌면 옛 세션의 로그인 정보로 요청이 처리된다.
	@Test
	void 계정_확인은_최대_유지_시간_확인_뒤_로그인_정보를_읽기_전에_돈다() {
		List<String> filters = securityFilterChain.getFilters()
			.stream()
			.map(filter -> filter.getClass().getSimpleName())
			.toList();

		assertThat(filters).containsSubsequence("SessionMaxLifetimeFilter", "SessionAccountFilter",
				"SecurityContextHolderFilter");
	}

	// 막힌 세션은 지워진다. 같은 쿠키로 다시 와도 로그인하지 않은 요청이다.
	private void expectLoggedOut(Cookie session) throws Exception {
		mockMvc.perform(get("/api/v1/auth/me").cookie(session))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("COMMON_UNAUTHENTICATED"));
		mockMvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isUnauthorized());
	}

	// 세션은 건드리지 않고 계정만 고친다(AccountService를 거치지 않는다).
	private void change(User account, Consumer<User> change) {
		transaction.executeWithoutResult(status -> change.accept(users.findById(account.getId()).orElseThrow()));
	}

	private User saved(UserRole role, Long campusId) {
		String loginId = "check-" + UUID.randomUUID().toString().substring(0, 8);
		return users.saveAndFlush(User.create(loginId, passwordHash, "김직원", role, campusId, null));
	}

	private Cookie login(User account) throws Exception {
		Cookie session = mockMvc
			.perform(post("/api/v1/auth/login").with(csrfToken(mockMvc))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"loginId\":\"" + account.getLoginId() + "\",\"password\":\"" + PASSWORD + "\"}"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getCookie(SESSION);
		assertThat(session).as("로그인 응답의 세션 쿠키").isNotNull();
		return session;
	}

}
