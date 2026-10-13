package com.jey.core.auth.web;

import java.util.UUID;

import com.jey.TestcontainersConfiguration;
import com.jey.core.auth.UserSessions;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import static com.jey.TestCsrf.csrfToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 요청마다의 계정 확인(ADR-0031)이 장애 속에서 어떻게 답하는지를 실제 요청으로 본다.
// 저장소와 세션 끊기를 스파이로 바꿔 끼우므로 스프링 컨텍스트가 따로 뜬다. 그래서 SessionAccountCheckTest와 클래스를 나눴다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SessionAccountCheckFailureTest {

	private static final String SESSION = "SESSION";

	private static final String PASSWORD = "first-password-123";

	// 비밀번호 해시는 계산이 느리다. 테스트마다 다시 만들지 않는다.
	private static String passwordHash;

	@Autowired
	MockMvc mockMvc;

	@MockitoSpyBean
	UserRepository users;

	@MockitoSpyBean
	UserSessions userSessions;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	StringRedisTemplate redis;

	@BeforeEach
	void setUp() {
		// 앞에서 돈 테스트가 이 스레드에 로그인 정보를 남겼으면 MockMvc가 그것을 모든 요청의 로그인 정보로 쓴다.
		TestSecurityContextHolder.clearContext();
		redis.delete(redis.keys("jey:auth:login-attempts:*"));
		redis.delete(redis.keys("jey:auth:account-limit:*"));
		if (passwordHash == null) {
			passwordHash = passwordEncoder.encode(PASSWORD);
		}
	}

	// 통과시키면 그동안 차단이 풀리고, 세션을 끊으면 장애가 "모두 로그아웃됐다"로 보인다.
	// 필터에서 난 예외라 컨트롤러의 예외 처리기를 거치지 않는다. 그래도 다른 오류와 같은 형식으로 나가야 한다.
	@Test
	void 계정을_읽지_못하면_서버_오류로_답하고_DB가_돌아오면_같은_세션으로_일한다() throws Exception {
		User staff = saved();
		Cookie session = login(staff);
		doThrow(new DataAccessResourceFailureException("DB 장애")).when(users).findById(staff.getId());

		mockMvc.perform(get("/api/v1/auth/me").cookie(session))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("COMMON_INTERNAL_ERROR"))
			.andExpect(jsonPath("$.requestId").exists());
		// DB를 쓰지 않는 요청과 공개 경로도 세션 쿠키가 실려 있으면 같이 실패한다(ADR-0031의 포기한 것).
		mockMvc.perform(post("/api/v1/auth/logout").cookie(session).with(csrfToken(mockMvc)))
			.andExpect(status().isInternalServerError());
		mockMvc.perform(get("/api/v1/auth/csrf").cookie(session)).andExpect(status().isInternalServerError());
		// 세션 쿠키가 없는 요청은 계정을 읽지 않는다.
		mockMvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isNoContent());

		reset(users);

		mockMvc.perform(get("/api/v1/auth/me").cookie(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.loginId").value(staff.getLoginId()));
	}

	// 다른 세션을 끊는 일이 아무것도 못 했다(세션 저장소 장애). 그래도 옛 비밀번호로 로그인된 다른 세션은 일하지 못해야 한다.
	@Test
	void 본인이_비밀번호를_바꾸면_다른_세션은_끊지_못했더라도_막히고_지금_세션은_남는다() throws Exception {
		User staff = saved();
		Cookie here = login(staff);
		Cookie otherDevice = login(staff);
		doReturn(0).when(userSessions).terminateOthers(eq(staff.getId()), anyString());

		Cookie renewed = mockMvc
			.perform(post("/api/v1/auth/password").cookie(here)
				.with(csrfToken(mockMvc))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"my-new-password-1\"}"))
			.andExpect(status().isNoContent())
			.andReturn()
			.getResponse()
			.getCookie(SESSION);

		assertThat(renewed).as("ID가 바뀐 지금 세션의 쿠키").isNotNull();
		mockMvc.perform(get("/api/v1/auth/me").cookie(renewed)).andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/auth/me").cookie(otherDevice)).andExpect(status().isUnauthorized());
	}

	private User saved() {
		String loginId = "fail-" + UUID.randomUUID().toString().substring(0, 8);
		return users.saveAndFlush(User.create(loginId, passwordHash, "김직원", UserRole.STAFF, 1L, null));
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
