package com.jey.dongtanpass.web;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import com.jey.TestcontainersConfiguration;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.Money;
import com.jey.core.shared.PhoneNumber;
import com.jey.core.shared.PhoneProtector;
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
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 데스크가 학생을 등록하기 전에 등록 폼의 이름과 학부모 번호가 패스와 맞는지 미리 맞춰 본다(ADR-0033).
// 이름과 번호는 각각 따로 답한다(틀린 쪽을 폼에서 바로 고치게). 최종 판단은 사용 처리가 한다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class HolderCheckApiTest {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	// 패스를 받는 지점(시드)과 받지 않는 지점
	private static final long PROVIDER = 1L;

	private static final long OTHER_CAMPUS = 2L;

	private static final long PARTNER = 901_601L;

	private static final String NAME = "김하윤";

	private static final SecureRandom RANDOM = new SecureRandom();

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PassRepository passes;

	@Autowired
	PhoneProtector phoneProtector;

	@Autowired
	Clock clock;

	@Autowired
	StringRedisTemplate redis;

	private String phone;

	private Pass pass;

	@BeforeEach
	void setUp() {
		TestSecurityContextHolder.clearContext();
		upsertPartner(jdbc, PARTNER, true);
		phone = newPhone();
		pass = save(NAME, phone);
	}

	@ParameterizedTest(name = "{0} / {1} → 이름 {2}, 번호 {3}")
	@CsvSource({ "김하윤,same,true,true", "김하은,same,false,true", "김하윤,other,true,false", "김하은,other,false,false" })
	void 이름과_번호를_따로_답한다(String name, String which, boolean nameMatches, boolean phoneMatches)
			throws Exception {
		String typed = "same".equals(which) ? phone : newPhone();

		check(pass.getId(), body(name, typed)).andExpect(status().isOk())
			.andExpect(jsonPath("$.nameMatches").value(nameMatches))
			.andExpect(jsonPath("$.phoneMatches").value(phoneMatches));
	}

	// 등록 폼에는 띄어 쓰거나 다른 입력기로 적을 수 있다. 공백(전각 포함)을 모두 빼고 NFC로 모아 비교한다.
	@ParameterizedTest
	@ValueSource(strings = { "김 하윤", " 김하윤 ", "김\u3000하윤" })
	void 띄어쓰기와_전각_공백이_달라도_같은_이름이다(String name) throws Exception {
		check(pass.getId(), body(name, phone)).andExpect(status().isOk())
			.andExpect(jsonPath("$.nameMatches").value(true));
	}

	@Test
	void 자모를_나눠_적은_같은_이름도_같은_이름이다() throws Exception {
		check(pass.getId(), body(Normalizer.normalize(NAME, Normalizer.Form.NFD), phone)).andExpect(status().isOk())
			.andExpect(jsonPath("$.nameMatches").value(true));
	}

	// 학생에게 학부모가 여럿이면 폼의 번호를 모두 보낸다. 패스를 받을 때 적은 번호가 그중 하나면 맞다.
	@Test
	void 번호_여럿_중_하나만_맞아도_맞다() throws Exception {
		String hyphenated = phone.substring(0, 3) + "-" + phone.substring(3, 7) + "-" + phone.substring(7);

		check(pass.getId(), body(NAME, newPhone(), newPhone(), newPhone(), hyphenated)).andExpect(status().isOk())
			.andExpect(jsonPath("$.phoneMatches").value(true));
		check(pass.getId(), body(NAME, newPhone(), newPhone())).andExpect(status().isOk())
			.andExpect(jsonPath("$.phoneMatches").value(false));
	}

	@ParameterizedTest(name = "{0}, 만료일 오늘{1}일 → {2}")
	@CsvSource({ "ISSUED,-1,DONGTANPASS_PASS_EXPIRED", "CANCELED,10,DONGTANPASS_PASS_CANCELED",
			"REDEEMED,10,DONGTANPASS_PASS_ALREADY_REDEEMED" })
	void 쓸_수_없는_패스는_사용_처리와_같은_409다(String stored, int expiresInDays, String code) throws Exception {
		jdbc.update("UPDATE dongtanpass_pass SET status = ?, expires_on = ? WHERE id = ?", stored,
				today().plusDays(expiresInDays), pass.getId());

		check(pass.getId(), body(NAME, phone)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(code));
	}

	@Test
	void 없는_패스는_404다() throws Exception {
		check(Long.MAX_VALUE, body(NAME, phone)).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_NOT_FOUND"));
	}

	@Test
	void 패스를_받지_않는_지점의_직원은_403이다() throws Exception {
		sendAs(pass.getId(), body(NAME, phone), UserRole.STAFF, OTHER_CAMPUS, newAccount())
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_CAMPUS_NOT_PROVIDING"));
	}

	@Test
	void 관리자는_맞춰_볼_수_있고_연계_학원_계정은_403이다() throws Exception {
		sendAs(pass.getId(), body(NAME, phone), UserRole.ADMIN, null, newAccount()).andExpect(status().isOk());
		sendAs(pass.getId(), body(NAME, phone), UserRole.PARTNER, null, newAccount())
			.andExpect(status().isForbidden());
	}

	@Test
	void 로그인하지_않으면_401이다() throws Exception {
		mockMvc
			.perform(post(path(pass.getId())).with(csrfToken(mockMvc))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(NAME, phone)))
			.andExpect(status().isUnauthorized());
	}

	// 번호는 1~4개다(학생 한 명의 학부모는 넷까지). 빈 칸·형식이 틀린 번호는 500이 아니라 그 칸의 400이다.
	@ParameterizedTest
	@ValueSource(strings = { "[]", "[\"01011112222\",\"01011112223\",\"01011112224\",\"01011112225\",\"01011112226\"]",
			"[null]", "[\"abc\"]", "\"01011112222\"", "null" })
	void 번호_목록이_틀리면_그_칸의_400이다(String phones) throws Exception {
		check(pass.getId(), "{\"studentName\":\"" + NAME + "\",\"guardianPhones\":" + phones + "}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value(startsWith("guardianPhones")));
	}

	@Test
	void 이름이_없으면_그_칸의_400이다() throws Exception {
		check(pass.getId(), "{\"guardianPhones\":[\"" + phone + "\"]}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("studentName"));
	}

	// 빈 이름·공백(NBSP·전각 포함)뿐인 이름은 그 칸의 오류다(등록 폼의 이름 칸 밑에 보여 준다). 공백의 기준은 이름 비교(StudentNameKey)와 같다.
	@ParameterizedTest
	@ValueSource(strings = { "", " ", "\u00A0", "\u3000\u3000" })
	void 비었거나_공백뿐인_이름은_그_칸의_400이다(String name) throws Exception {
		check(pass.getId(), body(name, phone)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("studentName"));
	}

	@Test
	void 모르는_칸이_있으면_400이다() throws Exception {
		check(pass.getId(), "{\"studentName\":\"" + NAME + "\",\"guardianPhones\":[\"" + phone + "\"],\"phone\":\"x\"}")
			.andExpect(status().isBadRequest());
	}

	// 이름과 번호를 주소에 실어 보내면 앞단의 접근 로그에 남는다. 조용히 무시하지 않고 거부해서 드러나게 한다(ADR-0032).
	@Test
	void 쿼리_문자열이_실려_오면_400이다() throws Exception {
		sendAs(path(pass.getId()) + "?studentName=" + NAME, body(NAME, phone), UserRole.STAFF, PROVIDER, newAccount())
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON_BAD_REQUEST"));
	}

	// 맞든 틀리든 센다. 없는 패스·쓸 수 없는 패스로 넣어 보는 것도 센다(조회 전에 센다).
	// 막힌 뒤에는 맞는 요청도 확인하지 않고, 처음 막힌 것만 감사 로그에 남는다. 패스 번호 확인의 한도와는 따로 센다.
	@Test
	void 본인_미리_확인은_계정마다_정한_횟수까지이고_넘으면_429다() throws Exception {
		long account = newAccount();
		Pass canceled = save(NAME, phone);
		jdbc.update("UPDATE dongtanpass_pass SET status = 'CANCELED' WHERE id = ?", canceled.getId());
		for (int i = 0; i < 30; i++) {
			switch (i % 4) {
				case 0 -> sendAs(pass.getId(), body(NAME, phone), UserRole.STAFF, PROVIDER, account)
					.andExpect(status().isOk());
				case 1 -> sendAs(pass.getId(), body("김하은", newPhone()), UserRole.STAFF, PROVIDER, account)
					.andExpect(status().isOk());
				case 2 -> sendAs(Long.MAX_VALUE, body(NAME, phone), UserRole.STAFF, PROVIDER, account)
					.andExpect(status().isNotFound());
				default -> sendAs(canceled.getId(), body(NAME, phone), UserRole.STAFF, PROVIDER, account)
					.andExpect(status().isConflict());
			}
		}

		String retryAfter = sendAs(pass.getId(), body(NAME, phone), UserRole.STAFF, PROVIDER, account)
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_HOLDER_CHECK_LIMITED"))
			.andReturn()
			.getResponse()
			.getHeader("Retry-After");
		sendAs(pass.getId(), body(NAME, phone), UserRole.STAFF, PROVIDER, account)
			.andExpect(status().isTooManyRequests());

		assertThat(Long.parseLong(retryAfter)).isPositive();
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM core_audit_log WHERE action = 'dongtanpass.holder-check-limited' AND actor_id = ?",
				Integer.class, account))
			.isEqualTo(1);
		sendAs(pass.getId(), body(NAME, phone), UserRole.STAFF, PROVIDER, newAccount()).andExpect(status().isOk());
		mockMvc
			.perform(post("/api/v1/dongtanpass/passes/verify").with(securityContext(login(UserRole.STAFF, PROVIDER, account)))
				.with(csrfToken(mockMvc))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"passNo\":\"" + pass.getPassNo() + "\"}"))
			.andExpect(status().isOk());
	}

	// 지점을 먼저 본다. 패스를 받지 않는 지점의 직원은 넣어 보는 것 자체를 할 수 없다.
	@Test
	void 패스를_받지_않는_지점의_직원은_세지_않는다() throws Exception {
		long account = newAccount();

		sendAs(pass.getId(), body(NAME, phone), UserRole.STAFF, OTHER_CAMPUS, account).andExpect(status().isForbidden());

		assertThat(redis.hasKey("jey:auth:account-limit:dongtanpass.holder-check:" + account)).isFalse();
	}

	@Test
	void 응답과_로그에_이름과_번호가_없다(CapturedOutput output) throws Exception {
		String response = check(pass.getId(), body(NAME, phone)).andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		assertThat(response).doesNotContain(NAME).doesNotContain(phone);
		assertThat(output.getAll()).doesNotContain(NAME).doesNotContain(phone).doesNotContain(phone.substring(3));
	}

	private ResultActions check(long passId, String body) throws Exception {
		return sendAs(passId, body, UserRole.STAFF, PROVIDER, newAccount());
	}

	private ResultActions sendAs(long passId, String body, UserRole role, Long campusId, long accountId)
			throws Exception {
		return sendAs(path(passId), body, role, campusId, accountId);
	}

	// 한 테스트 안에서 계정을 바꿔 부른다. Redis는 한 번 실행하는 동안 테스트끼리 함께 쓰므로 한도를 세는 요청은 테스트마다 새 계정으로 보낸다.
	private ResultActions sendAs(String uri, String body, UserRole role, Long campusId, long accountId)
			throws Exception {
		return mockMvc.perform(post(uri).with(securityContext(login(role, campusId, accountId)))
			.with(csrfToken(mockMvc))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private static SecurityContext login(UserRole role, Long campusId, long accountId) {
		AuthenticatedUser user = new AuthenticatedUser(accountId, "desk-staff", "데스크", role, campusId,
				role == UserRole.PARTNER ? PARTNER : null);
		SecurityContext login = SecurityContextHolder.createEmptyContext();
		login.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null,
				List.of(new SimpleGrantedAuthority(role.authority()))));
		return login;
	}

	private static String path(long passId) {
		return "/api/v1/dongtanpass/passes/" + passId + "/holder-check";
	}

	private static String body(String name, String... phones) {
		return "{\"studentName\":\"" + name + "\",\"guardianPhones\":[\"" + String.join("\",\"", phones) + "\"]}";
	}

	private Pass save(String name, String guardianPhone) {
		return passes.saveAndFlush(Pass.issue(PARTNER, PassNumber.generate(RANDOM), LinkToken.generate(RANDOM).hash(),
				name, phoneProtector.fullIndex(PhoneNumber.of(guardianPhone)), clock.instant(), SEOUL, today(),
				Money.of(150000), PaymentMethod.CARD));
	}

	private LocalDate today() {
		return LocalDate.now(clock);
	}

	private static String newPhone() {
		return "0109" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
	}

	// 다른 테스트와 겹치지 않는 큰 수를 무작위로 쓴다.
	private static long newAccount() {
		return 9_016_000_000L + RANDOM.nextInt(1_000_000_000);
	}

}
