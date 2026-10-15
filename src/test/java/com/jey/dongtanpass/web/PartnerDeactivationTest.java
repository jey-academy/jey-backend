package com.jey.dongtanpass.web;

import java.util.Map;
import java.util.UUID;

import com.jey.TestcontainersConfiguration;
import com.jey.core.auth.AccountService;
import com.jey.core.auth.api.NewPartnerAccount;
import com.jey.core.auth.api.PartnerAccounts;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import static com.jey.TestCsrf.csrfToken;
import static com.jey.TestIdempotency.idempotencyKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 연계 학원을 끄고 켠다 (ADR-0034). 실제 세션 쿠키로 로그인한다: 끄면 그 학원 계정의 세션이 정말 막히는지가 핵심이다.
// 학원을 끄는 것(먼저 커밋)과 계정 차단(그 뒤 하나씩)은 한 번에 끝나지 않는다. 막는 기준은 학원 상태이고,
// 계정 차단이 실패하거나 늦어도 끈 학원의 계정은 패스 기능을 쓰지 못한다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class PartnerDeactivationTest {

	private static final String PARTNERS = "/api/v1/dongtanpass/partners";

	private static final String AREA = "/api/v1/dongtanpass/test-partner-area";

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

	@Autowired
	JsonMapper jsonMapper;

	@Autowired
	PartnerAccounts partnerAccounts;

	@MockitoSpyBean
	AccountService accountService;

	private Cookie adminSession;

	@BeforeEach
	void setUp() throws Exception {
		// 앞에서 돈 테스트가 이 스레드에 남긴 로그인 정보를 MockMvc가 모든 요청에 쓴다. 이 클래스는 실제 쿠키로만 로그인한다.
		TestSecurityContextHolder.clearContext();
		redis.delete(redis.keys("jey:auth:login-attempts:*"));
		if (passwordHash == null) {
			passwordHash = passwordEncoder.encode(PASSWORD);
		}
		User admin = users
			.saveAndFlush(User.create(newLoginId("admin"), passwordHash, "박원장", UserRole.ADMIN, null, null));
		adminSession = login(admin.getLoginId(), PASSWORD);
	}

	@Test
	void 학원을_끄면_그_학원_계정의_세션이_끊기고_다시_로그인할_수_없다(CapturedOutput output) throws Exception {
		long partnerId = newPartner();
		Account first = newAccount(partnerId);
		Account second = newAccount(partnerId);
		Cookie firstSession = login(first.loginId(), PASSWORD);
		Cookie secondSession = login(second.loginId(), PASSWORD);
		mockMvc.perform(get(AREA).cookie(firstSession)).andExpect(status().isOk());

		asAdmin(post(PARTNERS + "/{id}/deactivate", partnerId)).andExpect(status().isOk())
			.andExpect(jsonPath("$.active").value(false));

		mockMvc.perform(get("/api/v1/auth/me").cookie(firstSession)).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/auth/me").cookie(secondSession)).andExpect(status().isUnauthorized());
		tryLogin(first.loginId(), PASSWORD).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
		assertThat(partnerRecords("dongtanpass.partner-deactivated", partnerId)).isEqualTo(1);
		assertThat(accountRecords("account.disabled", first.id())).isEqualTo(1);
		assertThat(accountRecords("account.disabled", second.id())).isEqualTo(1);
		assertThat(output).contains("연계 학원 계정을 한꺼번에 차단했다: partnerId=" + partnerId + " disabled=2");
	}

	// 계정 하나를 차단하다 실패한 경우. 학원은 이미 꺼져 있고, 끄기를 다시 부르면 남은 계정만 차단한다.
	@Test
	void 계정_차단이_중간에_실패하면_실패로_답하고_다시_끄면_남은_계정만_차단한다(CapturedOutput output) throws Exception {
		long partnerId = newPartner();
		Account first = newAccount(partnerId);
		Account second = newAccount(partnerId);
		doThrow(new IllegalStateException("세션 저장소 대신 일부러 낸 장애")).doCallRealMethod()
			.when(accountService)
			.disable(second.id());

		asAdmin(post(PARTNERS + "/{id}/deactivate", partnerId)).andExpect(status().isInternalServerError());

		assertThat(partnerActive(partnerId)).isFalse();
		assertThat(accountStatus(first.id())).isEqualTo("DISABLED");
		assertThat(accountStatus(second.id())).isEqualTo("ACTIVE");
		// 어디까지 됐는지 로그만으로 알 수 있어야 한다(감사 로그를 뒤지지 않고).
		assertThat(output).contains("연계 학원 계정을 한꺼번에 차단하다 멈췄다")
			.contains("partnerId=" + partnerId + " failedAccountId=" + second.id() + " disabled=1/2");

		asAdmin(post(PARTNERS + "/{id}/deactivate", partnerId)).andExpect(status().isOk())
			.andExpect(jsonPath("$.active").value(false));

		assertThat(accountStatus(second.id())).isEqualTo("DISABLED");
		assertThat(accountRecords("account.disabled", first.id())).isEqualTo(1);
		assertThat(partnerRecords("dongtanpass.partner-deactivated", partnerId)).isEqualTo(1);
	}

	// 학원이 켜져 있는지 본 직후에 꺼지면, 그 사이에 만든 계정은 차단되지 않고 남는다. 그 계정도 패스 기능은 쓰지 못한다.
	@Test
	void 끈_뒤에_남은_계정은_패스_기능을_쓰지_못하고_다시_끄면_차단된다() throws Exception {
		long partnerId = newPartner();
		asAdmin(post(PARTNERS + "/{id}/deactivate", partnerId)).andExpect(status().isOk());
		// 끄기와 겹쳐 만들어진 계정을 흉내 낸다(패스 모듈의 확인을 거치지 않고 기초 모듈에서 바로 만든다).
		String loginId = newLoginId("partner");
		long lateId = partnerAccounts.create(new NewPartnerAccount(partnerId, loginId, "늦은계정", PASSWORD)).id();
		Cookie lateSession = login(loginId, PASSWORD);

		mockMvc.perform(get(AREA).cookie(lateSession)).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PARTNER_INACTIVE"));

		asAdmin(post(PARTNERS + "/{id}/deactivate", partnerId)).andExpect(status().isOk());

		mockMvc.perform(get("/api/v1/auth/me").cookie(lateSession)).andExpect(status().isUnauthorized());
		assertThat(accountStatus(lateId)).isEqualTo("DISABLED");
		assertThat(partnerRecords("dongtanpass.partner-deactivated", partnerId)).isEqualTo(1);
	}

	// 계정은 저절로 풀리지 않는다. 학원을 끄기 전에 따로 차단해 둔 계정까지 풀리면 안 된다.
	@Test
	void 학원을_다시_켜도_계정은_차단된_채로_남는다() throws Exception {
		long partnerId = newPartner();
		Account account = newAccount(partnerId);
		asAdmin(post(PARTNERS + "/{id}/deactivate", partnerId)).andExpect(status().isOk());

		asAdmin(post(PARTNERS + "/{id}/activate", partnerId)).andExpect(status().isOk())
			.andExpect(jsonPath("$.active").value(true));
		asAdmin(post(PARTNERS + "/{id}/activate", partnerId)).andExpect(status().isOk());

		assertThat(partnerActive(partnerId)).isTrue();
		assertThat(accountStatus(account.id())).isEqualTo("DISABLED");
		assertThat(partnerRecords("dongtanpass.partner-activated", partnerId)).isEqualTo(1);
	}

	private long newPartner() throws Exception {
		String body = asAdmin(post(PARTNERS).with(idempotencyKey()), Map.of("name", "끄기확인-" + uuid()))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return jsonMapper.readTree(body).get("id").asLong();
	}

	private Account newAccount(long partnerId) throws Exception {
		String loginId = newLoginId("partner");
		String body = asAdmin(post(PARTNERS + "/{id}/accounts", partnerId),
				Map.of("loginId", loginId, "name", "연계학원", "password", PASSWORD))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return new Account(jsonMapper.readTree(body).get("id").asLong(), loginId);
	}

	private ResultActions asAdmin(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
			throws Exception {
		return asAdmin(request, Map.of());
	}

	private ResultActions asAdmin(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
			Map<String, ?> body) throws Exception {
		return mockMvc.perform(request.cookie(adminSession)
			.with(csrfToken(mockMvc))
			.contentType(MediaType.APPLICATION_JSON)
			.content(jsonMapper.writeValueAsString(body)));
	}

	private Cookie login(String loginId, String password) throws Exception {
		Cookie session = tryLogin(loginId, password).andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getCookie("SESSION");
		assertThat(session).as("로그인 응답의 세션 쿠키").isNotNull();
		return session;
	}

	private ResultActions tryLogin(String loginId, String password) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/login").with(csrfToken(mockMvc))
			.contentType(MediaType.APPLICATION_JSON)
			.content(jsonMapper.writeValueAsString(Map.of("loginId", loginId, "password", password))));
	}

	private boolean partnerActive(long partnerId) {
		return jdbc.queryForObject("SELECT active FROM dongtanpass_partner WHERE id = ?", Boolean.class, partnerId);
	}

	private String accountStatus(long accountId) {
		return jdbc.queryForObject("SELECT status FROM core_user WHERE id = ?", String.class, accountId);
	}

	private int partnerRecords(String action, long partnerId) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM core_audit_log WHERE action = ? AND target_type = 'partner' AND target_id = ?",
				Integer.class, action, partnerId);
	}

	private int accountRecords(String action, long accountId) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM core_audit_log WHERE action = ? AND target_type = 'account' AND target_id = ?",
				Integer.class, action, accountId);
	}

	private static String newLoginId(String prefix) {
		return prefix + "-" + uuid();
	}

	private static String uuid() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	private record Account(long id, String loginId) {
	}

}
