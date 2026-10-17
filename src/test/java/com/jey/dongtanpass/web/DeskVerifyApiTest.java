package com.jey.dongtanpass.web;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.Money;
import com.jey.dongtanpass.application.PassBenefits;
import com.jey.dongtanpass.application.PassQrCodes;
import com.jey.dongtanpass.domain.LinkToken;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassNumber;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static com.jey.TestCsrf.csrfToken;
import static com.jey.dongtanpass.DongtanpassFixtures.upsertPartner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 데스크 직원이 학부모의 QR 토큰으로 패스를 확인한다. 토큰은 한 번만 통과한다(ADR-0010).
// 패스를 받는 지점의 직원과 관리자만 확인한다. 학생 이름은 가린다(직원이 등록 폼에 적은 이름을 사용 처리에서 맞춰 보므로).
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class DeskVerifyApiTest {

	private static final String VERIFY = "/api/v1/dongtanpass/passes/verify";

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	// 패스를 받는 지점(시드)과 받지 않는 지점
	private static final long PROVIDER = 1L;

	private static final long OTHER_CAMPUS = 2L;

	private static final long PARTNER = 901_401L;

	private static final SecureRandom RANDOM = new SecureRandom();

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PassRepository passes;

	@Autowired
	PassQrCodes passQrCodes;

	@Autowired
	PassBenefits passBenefits;

	@Autowired
	Clock clock;

	@Autowired
	StringRedisTemplate redis;

	@BeforeEach
	void setUp() {
		upsertPartner(jdbc, PARTNER, true);
		assertThat(passBenefits.current().campusId()).isEqualTo(PROVIDER);
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = PROVIDER, id = 901_410L)
	void QR_토큰으로_확인하면_패스_ID와_가린_정보를_준다(CapturedOutput output) throws Exception {
		Pass pass = save("김하윤");
		String token = qrOf(pass);

		verify(token).andExpect(status().isOk())
			.andExpect(jsonPath("$.passId").value(pass.getId()))
			.andExpect(jsonPath("$.passNo").value(pass.getPassNo()))
			.andExpect(jsonPath("$.partnerName").value("테스트학원-" + PARTNER))
			.andExpect(jsonPath("$.studentName").value("김*윤"))
			.andExpect(jsonPath("$.issuedOn").value(today().toString()))
			.andExpect(jsonPath("$.expiresOn").value(today().plusMonths(1).toString()))
			.andExpect(jsonPath("$.discountRate").value(0.05))
			.andExpect(content().string(not(containsString("김하윤"))))
			.andExpect(content().string(not(containsString(token))));
		assertThat(output.getAll()).doesNotContain(token);
	}

	// 토큰이 무효가 될 때까지 기억해야 한다. 짧게 기억하면 캡처한 QR이 그 뒤 다른 데스크에서 한 번 더 통과한다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = PROVIDER, id = 901_419L)
	void 확인한_토큰은_남은_수명만큼_기억한다() throws Exception {
		String token = qrOf(save("김하윤"));
		String tokenId = passQrCodes.verify(token, clock.instant()).orElseThrow().tokenId();

		verify(token).andExpect(status().isOk());

		assertThat(redis.getExpire("jey:dongtanpass:qr-used:" + tokenId, TimeUnit.SECONDS)).isGreaterThan(50L);
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = PROVIDER, id = 901_411L)
	void 같은_토큰은_한_번만_통과한다(CapturedOutput output) throws Exception {
		String token = qrOf(save("김하윤"));

		verify(token).andExpect(status().isOk());
		verify(token).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_QR_INVALID"));
		assertThat(output.getAll()).contains("reason=reused");
	}

	// 위조, 만료, 아무 글자 모두 같은 400이다. 어느 쪽인지는 로그에만 남는다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = PROVIDER, id = 901_412L)
	void 위조나_만료나_모양이_틀린_토큰은_본문까지_같은_400이다() throws Exception {
		Pass pass = save("김하윤");
		String valid = qrOf(pass);
		String[] parts = valid.split("\\.");
		String forged = parts[0] + "." + parts[1] + "." + flip(parts[2]);
		String expired = passQrCodes.issue(pass.getId(), clock.instant().minus(PassQrCodes.TTL)).token();

		String forgedBody = invalidBody(forged);

		assertThat(invalidBody(expired)).isEqualTo(forgedBody);
		assertThat(invalidBody("x")).isEqualTo(forgedBody);
		// 대조군: 같은 방법으로 받은 토큰은 통과한다
		verify(valid).andExpect(status().isOk());
	}

	@ParameterizedTest(name = "{0}, 만료일 오늘{1}일 → {2}")
	@CsvSource({ "ISSUED,-1,DONGTANPASS_PASS_EXPIRED", "CANCELED,10,DONGTANPASS_PASS_CANCELED",
			"REDEEMED,10,DONGTANPASS_PASS_ALREADY_REDEEMED" })
	@WithJeyUser(role = UserRole.STAFF, campusId = PROVIDER, id = 901_413L)
	void 쓸_수_없는_패스는_상태에_맞는_409다(String stored, int expiresInDays, String code) throws Exception {
		Pass pass = save("김하윤");
		String token = qrOf(pass);
		jdbc.update("UPDATE dongtanpass_pass SET status = ?, expires_on = ? WHERE id = ?", stored,
				today().plusDays(expiresInDays), pass.getId());

		verify(token).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code));
		// 쓸 수 없는 패스여도 확인을 거친 토큰은 쓴 것이다. 다시 읽으면 409가 아니라 400이다.
		verify(token).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_QR_INVALID"));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = PROVIDER, id = 901_414L)
	void 만료일_당일은_통과한다() throws Exception {
		Pass pass = save("김하윤");
		jdbc.update("UPDATE dongtanpass_pass SET expires_on = ? WHERE id = ?", today(), pass.getId());

		verify(qrOf(pass)).andExpect(status().isOk());
	}

	// 지점이 틀린 직원의 요청은 토큰을 쓰지 않는다. 학부모가 제공 지점 데스크로 가면 같은 QR이 통과한다.
	@Test
	void 패스를_받지_않는_지점의_직원은_403이고_토큰을_쓰지_않는다() throws Exception {
		String token = qrOf(save("김하윤"));

		verifyAs(token, UserRole.STAFF, OTHER_CAMPUS).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_CAMPUS_NOT_PROVIDING"));
		verifyAs(token, UserRole.STAFF, PROVIDER).andExpect(status().isOk());
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN, id = 901_415L)
	void 관리자는_확인할_수_있다() throws Exception {
		verify(qrOf(save("김하윤"))).andExpect(status().isOk());
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = PARTNER, id = 901_416L)
	void 연계_학원_계정은_403이다() throws Exception {
		verify(qrOf(save("김하윤"))).andExpect(status().isForbidden());
	}

	@Test
	void 로그인하지_않으면_401이다() throws Exception {
		TestSecurityContextHolder.clearContext();

		verify(qrOf(save("김하윤"))).andExpect(status().isUnauthorized());
	}

	// 정상 토큰은 약 90자다. 아주 긴 글자는 서명을 계산하기 전에 칸 오류로 거른다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = PROVIDER, id = 901_417L)
	void 토큰이_200자를_넘으면_그_칸의_400이고_아무것도_없으면_본문_오류다() throws Exception {
		verify("a".repeat(201)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("qrToken"));
		send("{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMON_BAD_REQUEST"));
	}

	@Test
	void 패스_번호로_확인하면_QR과_같은_본문을_준다() throws Exception {
		Pass pass = save("김하윤");

		sendAs(passNoBody(pass.getPassNo()), UserRole.STAFF, PROVIDER, newAccount()).andExpect(status().isOk())
			.andExpect(jsonPath("$.passId").value(pass.getId()))
			.andExpect(jsonPath("$.passNo").value(pass.getPassNo()))
			.andExpect(jsonPath("$.partnerName").value("테스트학원-" + PARTNER))
			.andExpect(jsonPath("$.studentName").value("김*윤"))
			.andExpect(jsonPath("$.issuedOn").value(today().toString()))
			.andExpect(jsonPath("$.expiresOn").value(today().plusMonths(1).toString()))
			.andExpect(jsonPath("$.discountRate").value(0.05));
	}

	@Test
	void 패스_번호로도_만료일_당일은_통과한다() throws Exception {
		Pass pass = save("김하윤");
		jdbc.update("UPDATE dongtanpass_pass SET expires_on = ? WHERE id = ?", today(), pass.getId());

		sendAs(passNoBody(pass.getPassNo()), UserRole.STAFF, PROVIDER, newAccount()).andExpect(status().isOk());
	}

	@Test
	void 없는_패스_번호는_404이고_쓸_수_없는_패스는_409다() throws Exception {
		long account = newAccount();
		Pass canceled = save("김하윤");
		jdbc.update("UPDATE dongtanpass_pass SET status = 'CANCELED' WHERE id = ?", canceled.getId());

		sendAs(passNoBody(PassNumber.generate(RANDOM)), UserRole.STAFF, PROVIDER, account)
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_NOT_FOUND"));
		sendAs(passNoBody(canceled.getPassNo()), UserRole.STAFF, PROVIDER, account).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_CANCELED"));
	}

	// 서버는 형식 그대로만 받는다(화면이 입력 칸에서 대문자로 바꿔 보낸다). 틀린 형식은 오타이므로 그 칸의 400으로 알린다.
	@ParameterizedTest
	@ValueSource(strings = { "DT-ABC23O", "dt-abc234", " DT-ABC234", "DT-ABC2345", "ABC234" })
	void 형식이_틀린_패스_번호는_그_칸의_400이다(String passNo) throws Exception {
		sendAs(passNoBody(passNo), UserRole.STAFF, PROVIDER, newAccount()).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("passNo"));
	}

	// 둘 중 하나만 받는다. 어느 칸이 틀린 것이 아니라 본문의 모양이 틀렸다.
	@Test
	void QR과_번호를_함께_보내면_본문_오류다() throws Exception {
		Pass pass = save("김하윤");

		sendAs("{\"qrToken\":\"" + qrOf(pass) + "\",\"passNo\":\"" + pass.getPassNo() + "\"}", UserRole.STAFF,
				PROVIDER, newAccount())
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON_BAD_REQUEST"));
	}

	// 맞든 틀리든 센다. 막힌 뒤에는 맞는 번호도 확인하지 않고, 처음 막힌 것만 감사 로그에 남는다.
	@Test
	void 패스_번호_확인은_계정마다_정한_횟수까지이고_넘으면_429다() throws Exception {
		long account = newAccount();
		Pass pass = save("김하윤");
		for (int i = 0; i < 60; i++) {
			String passNo = (i % 2 == 0) ? pass.getPassNo() : PassNumber.generate(RANDOM);
			sendAs(passNoBody(passNo), UserRole.STAFF, PROVIDER, account)
				.andExpect(status().is(i % 2 == 0 ? 200 : 404));
		}

		String retryAfter = sendAs(passNoBody(pass.getPassNo()), UserRole.STAFF, PROVIDER, account)
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_NO_VERIFY_LIMITED"))
			.andReturn()
			.getResponse()
			.getHeader("Retry-After");
		sendAs(passNoBody(pass.getPassNo()), UserRole.STAFF, PROVIDER, account)
			.andExpect(status().isTooManyRequests());

		assertThat(Long.parseLong(retryAfter)).isPositive();
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM core_audit_log WHERE action = 'dongtanpass.pass-no-verify-limited' AND actor_id = ?",
				Integer.class, account))
			.isEqualTo(1);
		// 다른 계정은 막히지 않는다. QR 확인은 세지 않는다.
		sendAs(passNoBody(pass.getPassNo()), UserRole.STAFF, PROVIDER, newAccount()).andExpect(status().isOk());
		sendAs("{\"qrToken\":\"" + qrOf(pass) + "\"}", UserRole.STAFF, PROVIDER, account)
			.andExpect(status().isOk());
	}

	// 지점을 먼저 본다. 패스를 받지 않는 지점의 직원은 번호를 넣어 보는 것 자체를 할 수 없다.
	@Test
	void 패스를_받지_않는_지점의_직원의_번호_확인은_세지_않는다() throws Exception {
		long account = newAccount();

		sendAs(passNoBody(save("김하윤").getPassNo()), UserRole.STAFF, OTHER_CAMPUS, account)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_CAMPUS_NOT_PROVIDING"));

		assertThat(redis.hasKey("jey:auth:account-limit:dongtanpass.pass-no-verify:" + account)).isFalse();
	}

	private String invalidBody(String token) throws Exception {
		return mockMvc
			.perform(post(VERIFY).with(csrfToken(mockMvc))
				.header("X-Request-Id", "desk-verify-400")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"qrToken\":\"" + token + "\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_QR_INVALID"))
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private ResultActions verify(String token) throws Exception {
		return send("{\"qrToken\":\"" + token + "\"}");
	}

	private ResultActions verifyAs(String token, UserRole role, long campusId) throws Exception {
		return sendAs("{\"qrToken\":\"" + token + "\"}", role, campusId, 901_418L);
	}

	// 한 테스트 안에서 계정을 바꿔 부른다. 횟수 한도는 Redis에 남으므로 한도를 세는 요청은 실행마다 새 계정으로 보낸다.
	private ResultActions sendAs(String body, UserRole role, long campusId, long accountId) throws Exception {
		AuthenticatedUser user = new AuthenticatedUser(accountId, "desk-staff", "데스크", role, campusId, null);
		SecurityContext login = SecurityContextHolder.createEmptyContext();
		login.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null,
				List.of(new SimpleGrantedAuthority(role.authority()))));
		return mockMvc.perform(post(VERIFY).with(securityContext(login))
			.with(csrfToken(mockMvc))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private static String passNoBody(String passNo) {
		return "{\"passNo\":\"" + passNo + "\"}";
	}

	// 901_4xx 대역 안의 고정 ID는 실행마다 같아 Redis의 횟수가 쌓인다. 다른 테스트와 겹치지 않는 큰 수를 쓴다.
	private static long newAccount() {
		return 9_014_000_000L + RANDOM.nextInt(1_000_000_000);
	}

	private ResultActions send(String body) throws Exception {
		return mockMvc.perform(
				post(VERIFY).with(csrfToken(mockMvc)).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private String qrOf(Pass pass) {
		return passQrCodes.issue(pass.getId(), clock.instant()).token();
	}

	private Pass save(String name) {
		return passes.saveAndFlush(Pass.issue(PARTNER, PassNumber.generate(RANDOM), LinkToken.generate(RANDOM).hash(),
				name, "phone-index", clock.instant(), SEOUL, today(), Money.of(150000), PaymentMethod.CARD));
	}

	private LocalDate today() {
		return LocalDate.now(clock);
	}

	// 첫 글자를 바꾼다(마지막 글자는 쓰이지 않는 비트가 있어 바꿔도 같은 바이트일 수 있다).
	private static String flip(String part) {
		return (part.charAt(0) == 'A' ? 'B' : 'A') + part.substring(1);
	}

}
