package com.jey.core.auth.web;

import java.util.UUID;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static com.jey.TestCsrf.csrfToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 계정 관리 API (ADR-0030). 실제로 로그인한 세션으로 부른다: 계정을 바꾸면 그 계정의 세션이 정말 끊기는지가 이 기능의 핵심이다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class AccountApiTest {

	private static final String SESSION = "SESSION";

	private static final String PASSWORD = "first-password-123";

	// 비밀번호 해시는 계산이 느리다. 테스트마다 다시 만들지 않는다.
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
	JdbcTemplate jdbc;

	private User admin;

	private Cookie adminSession;

	@BeforeEach
	void setUp() throws Exception {
		// 앞에서 돈 테스트가 이 스레드에 로그인 정보를 남겼으면 MockMvc가 그것을 모든 요청의 로그인 정보로 쓴다.
		// 이 클래스는 실제 세션 쿠키로만 로그인하므로 비우고 시작한다.
		TestSecurityContextHolder.clearContext();
		// 이 클래스의 요청은 모두 127.0.0.1에서 온다. 앞 테스트의 로그인 실패 횟수와 한도 횟수가 남으면 뒤 테스트가 429를 받는다.
		redis.delete(redis.keys("jey:auth:login-attempts:*"));
		redis.delete(redis.keys("jey:auth:account-limit:*"));
		if (passwordHash == null) {
			passwordHash = passwordEncoder.encode(PASSWORD);
		}
		admin = saved("admin", "박원장", UserRole.ADMIN, null);
		adminSession = login(admin.getLoginId(), PASSWORD);
	}

	@Test
	void 관리자가_직원_계정을_만들면_그_직원이_로그인할_수_있다() throws Exception {
		String loginId = newLoginId("desk");

		MvcResult created = asAdmin(post("/api/v1/accounts")).content(createJson(loginId, "김직원", "desk-password-123",
				"STAFF", "1"))
			.exec()
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.loginId").value(loginId))
			.andExpect(jsonPath("$.name").value("김직원"))
			.andExpect(jsonPath("$.role").value("STAFF"))
			.andExpect(jsonPath("$.campusId").value(1))
			.andExpect(jsonPath("$.status").value("ACTIVE"))
			.andExpect(jsonPath("$.createdAt").exists())
			.andExpect(jsonPath("$.password").doesNotExist())
			.andExpect(jsonPath("$.passwordHash").doesNotExist())
			.andReturn();

		assertThat(created.getResponse().getContentAsString()).doesNotContain("desk-password-123");
		Cookie staffSession = login(loginId, "desk-password-123");
		mockMvc.perform(get("/api/v1/auth/me").cookie(staffSession))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("STAFF"))
			.andExpect(jsonPath("$.campusId").value(1));
	}

	@Test
	void 관리자_계정은_지점_없이_만든다() throws Exception {
		asAdmin(post("/api/v1/accounts"))
			.content(createJson(newLoginId("owner"), "최원장", "owner-password-123", "ADMIN", "null"))
			.exec()
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.role").value("ADMIN"))
			.andExpect(jsonPath("$.campusId").value(nullValue()));
	}

	@Test
	void 직원은_계정을_보거나_만들거나_고칠_수_없다() throws Exception {
		User staff = saved("desk", "김직원", UserRole.STAFF, 1L);
		Cookie staffSession = login(staff.getLoginId(), PASSWORD);

		as(staffSession, get("/api/v1/accounts")).exec().andExpect(status().isForbidden());
		as(staffSession, get("/api/v1/accounts/" + admin.getId())).exec().andExpect(status().isForbidden());
		as(staffSession, post("/api/v1/accounts"))
			.content(createJson(newLoginId("desk"), "이직원", "desk-password-123", "STAFF", "1"))
			.exec()
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH_ROLE_NOT_ALLOWED"));
		as(staffSession, put("/api/v1/accounts/" + staff.getId())).content(updateJson("김직원", "ADMIN", "null"))
			.exec()
			.andExpect(status().isForbidden());
		as(staffSession, post("/api/v1/accounts/" + admin.getId() + "/disable")).exec()
			.andExpect(status().isForbidden());
		as(staffSession, post("/api/v1/accounts/" + admin.getId() + "/enable")).exec()
			.andExpect(status().isForbidden());
		as(staffSession, post("/api/v1/accounts/" + admin.getId() + "/password"))
			.content("{\"password\":\"taken-over-password\"}")
			.exec()
			.andExpect(status().isForbidden());

		assertThat(users.findById(admin.getId()).orElseThrow().isActive()).isTrue();
		assertThat(passwordEncoder.matches(PASSWORD, users.findById(admin.getId()).orElseThrow().getPasswordHash()))
			.isTrue();
	}

	@Test
	void 로그인하지_않으면_계정_API를_쓸_수_없다() throws Exception {
		mockMvc.perform(get("/api/v1/accounts")).andExpect(status().isUnauthorized());
		mockMvc
			.perform(post("/api/v1/accounts").with(csrfToken(mockMvc))
				.contentType(MediaType.APPLICATION_JSON)
				.content(createJson(newLoginId("desk"), "김직원", "desk-password-123", "STAFF", "1")))
			.andExpect(status().isUnauthorized());
		mockMvc
			.perform(post("/api/v1/auth/password").with(csrfToken(mockMvc))
				.contentType(MediaType.APPLICATION_JSON)
				.content(changeJson(PASSWORD, "my-new-password-1")))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void 이미_쓰는_아이디로는_만들_수_없다() throws Exception {
		User staff = saved("desk", "김직원", UserRole.STAFF, 1L);

		asAdmin(post("/api/v1/accounts"))
			.content(createJson(staff.getLoginId(), "이직원", "desk-password-123", "STAFF", "2"))
			.exec()
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("AUTH_LOGIN_ID_DUPLICATED"));
	}

	// 틀린 요청의 응답과 로그에도 비밀번호가 실리면 안 된다. 검증 실패 응답은 어느 칸이 틀렸는지만 말한다.
	@Test
	void 틀린_요청의_응답과_로그에_비밀번호가_없다(CapturedOutput output) throws Exception {
		String password = "secret-password-in-bad-request";

		String invalidLoginId = asAdmin(post("/api/v1/accounts"))
			.content(createJson("Desk Kim", "김직원", password, "STAFF", "1"))
			.exec()
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[*].field").value(hasItem("loginId")))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String mismatch = asAdmin(post("/api/v1/accounts"))
			.content(createJson(newLoginId("desk"), "김직원", password, "STAFF", "null"))
			.exec()
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("AUTH_ROLE_CAMPUS_MISMATCH"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String tooLong = asAdmin(post("/api/v1/accounts"))
			.content(createJson(newLoginId("desk"), "김직원", password.repeat(4), "STAFF", "1"))
			.exec()
			.andExpect(status().isBadRequest())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String malformed = asAdmin(post("/api/v1/accounts")).content("{\"password\":\"" + password + "\",")
			.exec()
			.andExpect(status().isBadRequest())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(invalidLoginId + mismatch + tooLong + malformed).doesNotContain(password);
		assertThat(output.getAll()).doesNotContain(password);
	}

	@Test
	void 받을_수_없는_역할과_비밀번호와_지점은_각각의_코드로_거부된다() throws Exception {
		asAdmin(post("/api/v1/accounts"))
			.content(createJson(newLoginId("partner"), "연계학원", "partner-password-1", "PARTNER", "null"))
			.exec()
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("AUTH_PARTNER_ACCOUNT_NOT_SUPPORTED"));
		asAdmin(post("/api/v1/accounts")).content(createJson(newLoginId("desk"), "김직원", "short", "STAFF", "1"))
			.exec()
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("AUTH_PASSWORD_NOT_ACCEPTABLE"));
		asAdmin(post("/api/v1/accounts"))
			.content(createJson(newLoginId("desk"), "김직원", "가".repeat(25), "STAFF", "1"))
			.exec()
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("AUTH_PASSWORD_NOT_ACCEPTABLE"));
		asAdmin(post("/api/v1/accounts"))
			.content(createJson(newLoginId("desk"), "김직원", "desk-password-123", "STAFF", "999999"))
			.exec()
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("AUTH_CAMPUS_NOT_FOUND"));
	}

	// 세션에는 로그인할 때의 역할과 지점이 들어 있다. 끊지 않으면 옮기기 전 지점의 권한으로 계속 일한다.
	@Test
	void 지점을_옮기면_그_직원은_로그아웃되고_다시_로그인하면_새_지점이다() throws Exception {
		User staff = saved("desk", "김직원", UserRole.STAFF, 1L);
		Cookie staffSession = login(staff.getLoginId(), PASSWORD);

		asAdmin(put("/api/v1/accounts/" + staff.getId())).content(updateJson("김직원", "STAFF", "2"))
			.exec()
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.campusId").value(2));

		mockMvc.perform(get("/api/v1/auth/me").cookie(staffSession)).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/auth/me").cookie(login(staff.getLoginId(), PASSWORD)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.campusId").value(2));
	}

	// 이름은 권한과 무관하다. 일하던 사람을 내보내지 않는다. 실제 세션 저장소에서 "옛 권한의 세션만 끊기"가 지금 세션을 남기는지도 여기서 본다.
	@Test
	void 이름만_고치면_그_직원은_로그인된_채로_남는다() throws Exception {
		User staff = saved("desk", "김직원", UserRole.STAFF, 1L);
		Cookie staffSession = login(staff.getLoginId(), PASSWORD);

		asAdmin(put("/api/v1/accounts/" + staff.getId())).content(updateJson("김데스크", "STAFF", "1"))
			.exec()
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("김데스크"));

		// 세션에 든 이름은 다음 로그인 때 바뀐다.
		mockMvc.perform(get("/api/v1/auth/me").cookie(staffSession))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("김직원"));
		mockMvc.perform(get("/api/v1/auth/me").cookie(login(staff.getLoginId(), PASSWORD)))
			.andExpect(jsonPath("$.name").value("김데스크"));
	}

	// 지점을 옮긴 요청이 세션을 끊다가 실패했다고 하자. 계정은 능동관인데 동탄관으로 로그인된 세션이 남아 있다.
	// 같은 내용으로 다시 고치면 바뀐 것은 없지만, 그 옛 세션은 끊겨야 한다.
	@Test
	void 바뀐_것이_없는_고치기도_옛_지점으로_로그인된_세션은_끊는다() throws Exception {
		User staff = saved("desk", "김직원", UserRole.STAFF, 1L);
		Cookie oldCampusSession = login(staff.getLoginId(), PASSWORD);
		jdbc.update("UPDATE core_user SET campus_id = 2 WHERE id = ?", staff.getId());
		Cookie currentSession = login(staff.getLoginId(), PASSWORD);

		asAdmin(put("/api/v1/accounts/" + staff.getId())).content(updateJson("김직원", "STAFF", "2"))
			.exec()
			.andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/auth/me").cookie(oldCampusSession)).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/auth/me").cookie(currentSession)).andExpect(status().isOk());
	}

	@Test
	void 차단하면_로그아웃되고_로그인할_수_없으며_풀면_다시_로그인한다() throws Exception {
		User staff = saved("desk", "김직원", UserRole.STAFF, 1L);
		Cookie staffSession = login(staff.getLoginId(), PASSWORD);

		asAdmin(post("/api/v1/accounts/" + staff.getId() + "/disable")).exec()
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DISABLED"));

		mockMvc.perform(get("/api/v1/auth/me").cookie(staffSession)).andExpect(status().isUnauthorized());
		tryLogin(staff.getLoginId(), PASSWORD).andExpect(status().isUnauthorized());

		asAdmin(post("/api/v1/accounts/" + staff.getId() + "/enable")).exec()
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ACTIVE"));

		tryLogin(staff.getLoginId(), PASSWORD).andExpect(status().isOk());
	}

	@Test
	void 자기_자신은_차단할_수_없다() throws Exception {
		asAdmin(post("/api/v1/accounts/" + admin.getId() + "/disable")).exec()
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("AUTH_SELF_CHANGE_NOT_ALLOWED"));

		mockMvc.perform(get("/api/v1/auth/me").cookie(adminSession)).andExpect(status().isOk());
	}

	@Test
	void 비밀번호를_다시_정하면_로그아웃되고_새_비밀번호로만_로그인한다() throws Exception {
		User staff = saved("desk", "김직원", UserRole.STAFF, 1L);
		Cookie staffSession = login(staff.getLoginId(), PASSWORD);

		asAdmin(post("/api/v1/accounts/" + staff.getId() + "/password"))
			.content("{\"password\":\"brand-new-password\"}")
			.exec()
			.andExpect(status().isNoContent());

		mockMvc.perform(get("/api/v1/auth/me").cookie(staffSession)).andExpect(status().isUnauthorized());
		tryLogin(staff.getLoginId(), PASSWORD).andExpect(status().isUnauthorized());
		tryLogin(staff.getLoginId(), "brand-new-password").andExpect(status().isOk());
	}

	// 바꾼 사람은 계속 쓰고, 같은 계정으로 로그인된 다른 화면(다른 기기, 훔친 세션)은 끊긴다.
	// 남긴 세션도 ID가 바뀐다. 그 세션의 쿠키를 베껴 갖고 있던 사본은 못 쓰게 된다.
	@Test
	void 자기_비밀번호를_바꾸면_지금_화면만_남고_다른_화면은_로그아웃된다() throws Exception {
		User staff = saved("desk", "김직원", UserRole.STAFF, 1L);
		Cookie here = login(staff.getLoginId(), PASSWORD);
		Cookie otherDevice = login(staff.getLoginId(), PASSWORD);

		Cookie renewed = as(here, post("/api/v1/auth/password")).content(changeJson(PASSWORD, "my-new-password-1"))
			.exec()
			.andExpect(status().isNoContent())
			.andReturn()
			.getResponse()
			.getCookie(SESSION);

		assertThat(renewed).as("바꾼 뒤에 받은 새 세션 쿠키").isNotNull();
		assertThat(renewed.getValue()).isNotEqualTo(here.getValue());
		mockMvc.perform(get("/api/v1/auth/me").cookie(renewed)).andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/auth/me").cookie(here)).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/auth/me").cookie(otherDevice)).andExpect(status().isUnauthorized());
		tryLogin(staff.getLoginId(), PASSWORD).andExpect(status().isUnauthorized());
		tryLogin(staff.getLoginId(), "my-new-password-1").andExpect(status().isOk());
	}

	@Test
	void 지금_비밀번호가_틀리면_바뀌지_않고_다섯_번을_넘으면_맞아도_막힌다() throws Exception {
		User staff = saved("desk", "김직원", UserRole.STAFF, 1L);
		Cookie session = login(staff.getLoginId(), PASSWORD);

		for (int i = 0; i < 5; i++) {
			as(session, post("/api/v1/auth/password")).content(changeJson("wrong-password-1", "my-new-password-1"))
				.exec()
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("AUTH_CURRENT_PASSWORD_MISMATCH"));
		}

		as(session, post("/api/v1/auth/password")).content(changeJson(PASSWORD, "my-new-password-1"))
			.exec()
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("AUTH_TOO_MANY_PASSWORD_ATTEMPTS"))
			.andExpect(header().exists("Retry-After"));
		// 막혔다고 로그아웃되지는 않는다. 비밀번호도 그대로다.
		mockMvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isOk());
		tryLogin(staff.getLoginId(), PASSWORD).andExpect(status().isOk());
	}

	@Test
	void 새_비밀번호가_지금과_같거나_규칙에_맞지_않으면_바꿀_수_없다() throws Exception {
		User staff = saved("desk", "김직원", UserRole.STAFF, 1L);
		Cookie session = login(staff.getLoginId(), PASSWORD);

		as(session, post("/api/v1/auth/password")).content(changeJson(PASSWORD, PASSWORD))
			.exec()
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("AUTH_PASSWORD_UNCHANGED"));
		as(session, post("/api/v1/auth/password")).content(changeJson(PASSWORD, "short"))
			.exec()
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("AUTH_PASSWORD_NOT_ACCEPTABLE"));
	}

	// 가장 위험한 방향이다. 관리자에서 내려간 사람의 세션이 관리자 권한으로 남으면 안 된다.
	@Test
	void 관리자를_직원으로_내리면_로그아웃되고_다시_로그인해도_계정을_관리할_수_없다() throws Exception {
		User other = saved("admin", "최원장", UserRole.ADMIN, null);
		Cookie otherSession = login(other.getLoginId(), PASSWORD);
		as(otherSession, get("/api/v1/accounts")).exec().andExpect(status().isOk());

		asAdmin(put("/api/v1/accounts/" + other.getId())).content(updateJson("최원장", "STAFF", "1"))
			.exec()
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("STAFF"))
			.andExpect(jsonPath("$.campusId").value(1));

		as(otherSession, get("/api/v1/accounts")).exec().andExpect(status().isUnauthorized());
		Cookie again = login(other.getLoginId(), PASSWORD);
		mockMvc.perform(get("/api/v1/auth/me").cookie(again)).andExpect(jsonPath("$.role").value("STAFF"));
		as(again, get("/api/v1/accounts")).exec().andExpect(status().isForbidden());
	}

	// 비밀번호를 바꾸면 남긴 세션의 ID가 바뀐다. ID가 바뀐 세션도 그 계정의 세션으로 찾아져서 차단할 때 끊겨야 한다.
	@Test
	void 비밀번호를_바꿔_ID가_바뀐_세션도_차단하면_끊긴다() throws Exception {
		User staff = saved("desk", "김직원", UserRole.STAFF, 1L);
		Cookie before = login(staff.getLoginId(), PASSWORD);
		Cookie renewed = as(before, post("/api/v1/auth/password")).content(changeJson(PASSWORD, "my-new-password-1"))
			.exec()
			.andExpect(status().isNoContent())
			.andReturn()
			.getResponse()
			.getCookie(SESSION);
		mockMvc.perform(get("/api/v1/auth/me").cookie(renewed)).andExpect(status().isOk());

		asAdmin(post("/api/v1/accounts/" + staff.getId() + "/disable")).exec().andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/auth/me").cookie(renewed)).andExpect(status().isUnauthorized());
	}

	// 형식이 틀린 값은 요청을 받는 자리에서 걸러낸다. 거르지 못하면 엔티티가 예외를 던져 500이 된다.
	@Test
	void 형식이_틀린_아이디와_이름과_페이지는_400이다() throws Exception {
		for (String loginId : new String[] { "ab", "a".repeat(51), ".desk", "Desk", "desk kim", "데스크" }) {
			asAdmin(post("/api/v1/accounts")).content(createJson(loginId, "김직원", "desk-password-123", "STAFF", "1"))
				.exec()
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
		}
		for (String name : new String[] { "", " ", "가".repeat(51) }) {
			asAdmin(post("/api/v1/accounts"))
				.content(createJson(newLoginId("desk"), name, "desk-password-123", "STAFF", "1"))
				.exec()
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
			asAdmin(put("/api/v1/accounts/" + admin.getId())).content(updateJson(name, "ADMIN", "null"))
				.exec()
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
		}
		asAdmin(post("/api/v1/accounts"))
			.content(createJson("abc", "가".repeat(50), "desk-password-123", "PARTNER", "null"))
			.exec()
			.andExpect(jsonPath("$.code").value("AUTH_PARTNER_ACCOUNT_NOT_SUPPORTED"));
		asAdmin(get("/api/v1/accounts").param("page", "-1")).exec().andExpect(status().isBadRequest());
		// 아주 큰 페이지는 조회가 서버 오류로 끝난다. 상한을 넘으면 조회하지 않고 거부한다.
		asAdmin(get("/api/v1/accounts").param("page", "10000")).exec().andExpect(status().isOk());
		asAdmin(get("/api/v1/accounts").param("page", "10001")).exec().andExpect(status().isBadRequest());
		asAdmin(get("/api/v1/accounts").param("page", "2147483647")).exec()
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
		asAdmin(get("/api/v1/accounts").param("size", "0")).exec().andExpect(status().isBadRequest());
		asAdmin(get("/api/v1/accounts").param("status", "DELETED")).exec().andExpect(status().isBadRequest());
	}

	// 비밀번호를 바꾸거나 다시 정하다 거부된 응답에도 보낸 비밀번호가 실리지 않는다.
	@Test
	void 비밀번호_변경이_거부된_응답에_비밀번호가_없다() throws Exception {
		User staff = saved("desk", "김직원", UserRole.STAFF, 1L);
		Cookie session = login(staff.getLoginId(), PASSWORD);
		String wrong = "wrong-current-password";
		String rejectedNew = "rejected\\nnew-password";

		String mismatch = as(session, post("/api/v1/auth/password")).content(changeJson(wrong, "my-new-password-1"))
			.exec()
			.andExpect(status().isBadRequest())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String notAcceptable = as(session, post("/api/v1/auth/password")).content(changeJson(PASSWORD, rejectedNew))
			.exec()
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("AUTH_PASSWORD_NOT_ACCEPTABLE"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String blank = as(session, post("/api/v1/auth/password")).content(changeJson(PASSWORD, ""))
			.exec()
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		String reset = asAdmin(post("/api/v1/accounts/" + staff.getId() + "/password"))
			.content("{\"password\":\"short-pw\"}")
			.exec()
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("AUTH_PASSWORD_NOT_ACCEPTABLE"))
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(mismatch + notAcceptable + blank + reset).doesNotContain(wrong)
			.doesNotContain("new-password")
			.doesNotContain(PASSWORD)
			.doesNotContain("short-pw");
	}

	@Test
	void 조건으로_계정_목록을_보고_하나를_본다() throws Exception {
		User staff = saved("desk", "김직원", UserRole.STAFF, 2L);

		asAdmin(get("/api/v1/accounts").param("role", "STAFF")
			.param("campusId", "2")
			.param("status", "ACTIVE")
			.param("size", "100")).exec()
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].id").value(hasItem(staff.getId().intValue())))
			.andExpect(jsonPath("$.items[0].passwordHash").doesNotExist())
			.andExpect(jsonPath("$.page").value(0))
			.andExpect(jsonPath("$.size").value(100))
			.andExpect(jsonPath("$.totalElements").isNumber());
		asAdmin(get("/api/v1/accounts/" + staff.getId())).exec()
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.loginId").value(staff.getLoginId()));
		asAdmin(get("/api/v1/accounts/999999999")).exec()
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
		asAdmin(get("/api/v1/accounts").param("size", "101")).exec().andExpect(status().isBadRequest());
	}

	private User saved(String prefix, String name, UserRole role, Long campusId) {
		return users.saveAndFlush(User.create(newLoginId(prefix), passwordHash, name, role, campusId, null));
	}

	private static String newLoginId(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
	}

	private Cookie login(String loginId, String password) throws Exception {
		Cookie session = tryLogin(loginId, password).andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getCookie(SESSION);
		assertThat(session).as("로그인 응답의 세션 쿠키").isNotNull();
		return session;
	}

	private ResultActions tryLogin(String loginId, String password) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/login").with(csrfToken(mockMvc))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + password + "\"}"));
	}

	private Call asAdmin(MockHttpServletRequestBuilder request) {
		return as(adminSession, request);
	}

	// 그 세션으로 보내는 요청. Idempotency-Key는 싣지 않는다(계정 API의 POST는 모두 받지 않는다).
	private Call as(Cookie session, MockHttpServletRequestBuilder request) {
		return new Call(request.cookie(session).contentType(MediaType.APPLICATION_JSON));
	}

	private final class Call {

		private final MockHttpServletRequestBuilder request;

		private Call(MockHttpServletRequestBuilder request) {
			this.request = request;
		}

		Call content(String json) {
			request.content(json);
			return this;
		}

		ResultActions exec() throws Exception {
			return mockMvc.perform(request.with(csrfToken(mockMvc)));
		}

	}

	private static String createJson(String loginId, String name, String password, String role, String campusId) {
		return "{\"loginId\":\"" + loginId + "\",\"name\":\"" + name + "\",\"password\":\"" + password
				+ "\",\"role\":\"" + role + "\",\"campusId\":" + campusId + "}";
	}

	private static String updateJson(String name, String role, String campusId) {
		return "{\"name\":\"" + name + "\",\"role\":\"" + role + "\",\"campusId\":" + campusId + "}";
	}

	private static String changeJson(String currentPassword, String newPassword) {
		return "{\"currentPassword\":\"" + currentPassword + "\",\"newPassword\":\"" + newPassword + "\"}";
	}

}
