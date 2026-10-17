package com.jey.dongtanpass.web;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static com.jey.TestCsrf.csrfToken;
import static com.jey.TestIdempotency.idempotencyKey;
import static com.jey.dongtanpass.DongtanpassFixtures.upsertPartner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 데스크가 방금 등록한 학생에게 패스를 쓴다(ADR-0033). 확인 순서가 오류를 정하고, 미리 확인을 거쳤어도 이름과 번호를 다시 본다.
// 학생은 테스트마다 새로 등록한다(만든 지 7일 안이어야 신규다). 계정 ID는 테스트마다 새로 만든다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class PassRedeemApiTest {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	// 패스를 받는 지점(시드)과 받지 않는 지점
	private static final long PROVIDER = 1L;

	private static final long OTHER_CAMPUS = 2L;

	private static final long PARTNER = 901_701L;

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

	private long staff;

	private String phone;

	private Pass pass;

	@BeforeEach
	void setUp() {
		TestSecurityContextHolder.clearContext();
		upsertPartner(jdbc, PARTNER, true);
		staff = newAccount();
		phone = newPhone();
		pass = save(NAME, phone);
	}

	@Test
	void 등록한_학생에게_패스를_쓴다() throws Exception {
		long student = register(NAME, PROVIDER, phone);

		redeem(pass.getId(), student).andExpect(status().isOk())
			.andExpect(jsonPath("$.passId").value(pass.getId()))
			.andExpect(jsonPath("$.studentId").value(student))
			.andExpect(jsonPath("$.redemptionId").isNumber())
			.andExpect(jsonPath("$.redeemedAt").isString());

		assertThat(statusOf(pass)).isEqualTo("REDEEMED");
		assertThat(jdbc.queryForList("SELECT * FROM dongtanpass_redemption WHERE pass_id = ?", pass.getId()))
			.singleElement()
			.satisfies(row -> assertThat(row).containsEntry("student_id", student)
				.containsEntry("campus_id", PROVIDER)
				.containsEntry("created_by", staff));
	}

	// 감사 로그는 이벤트로 남는다(대상은 패스, 지점은 쓴 지점, 행위자는 처리한 직원). 이름과 번호는 없다.
	@Test
	void 사용_처리는_감사_로그에_이벤트_이름으로_남고_이름과_번호는_없다() throws Exception {
		long student = register(NAME, PROVIDER, phone);

		redeem(pass.getId(), student).andExpect(status().isOk());

		await().atMost(Duration.ofSeconds(10))
			.until(() -> auditOf(pass), rows -> !rows.isEmpty());
		assertThat(auditOf(pass)).singleElement().satisfies(record -> {
			assertThat(record).containsEntry("actor_id", staff).containsEntry("campus_id", PROVIDER);
			assertThat(record.values()).map(String::valueOf)
				.noneMatch(value -> value.contains(phone) || value.contains(NAME));
		});
	}

	// 등록 폼에는 띄어 쓰거나 다른 입력기로 적을 수 있다. 미리 확인과 같은 기준이다.
	@ParameterizedTest
	@ValueSource(strings = { "김 하윤", "김　하윤", "NFD" })
	void 띄어쓰기나_자모가_달라도_같은_이름이면_쓴다(String typed) throws Exception {
		String name = "NFD".equals(typed) ? Normalizer.normalize(NAME, Normalizer.Form.NFD) : typed;
		long student = register(name, PROVIDER, phone);

		redeem(pass.getId(), student).andExpect(status().isOk());
	}

	// 학생의 학부모 중 한 명이 패스를 받을 때 적은 번호면 된다.
	@Test
	void 학부모_중_한_명의_번호가_맞으면_쓴다() throws Exception {
		long student = register(NAME, PROVIDER, newPhone(), phone);

		redeem(pass.getId(), student).andExpect(status().isOk());
	}

	@Test
	void 패스를_받지_않는_지점의_직원은_403이다() throws Exception {
		long student = register(NAME, PROVIDER, phone);

		redeemAs(pass.getId(), student, UserRole.STAFF, OTHER_CAMPUS).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_CAMPUS_NOT_PROVIDING"));
		assertThat(statusOf(pass)).isEqualTo("ISSUED");
	}

	@Test
	void 관리자는_쓸_수_있고_연계_학원_계정은_403이다() throws Exception {
		long student = register(NAME, PROVIDER, phone);

		redeemAs(pass.getId(), student, UserRole.PARTNER, null).andExpect(status().isForbidden());
		redeemAs(pass.getId(), student, UserRole.ADMIN, null).andExpect(status().isOk());
	}

	@Test
	void 없는_패스는_404다() throws Exception {
		long student = register(NAME, PROVIDER, phone);

		redeem(Long.MAX_VALUE, student).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PASS_NOT_FOUND"));
	}

	@ParameterizedTest(name = "{0}, 만료일 오늘{1}일 → {2}")
	@CsvSource({ "ISSUED,-1,DONGTANPASS_PASS_EXPIRED", "CANCELED,10,DONGTANPASS_PASS_CANCELED",
			"REDEEMED,10,DONGTANPASS_PASS_ALREADY_REDEEMED" })
	void 쓸_수_없는_패스는_409다(String stored, int expiresInDays, String code) throws Exception {
		long student = register(NAME, PROVIDER, phone);
		jdbc.update("UPDATE dongtanpass_pass SET status = ?, expires_on = ? WHERE id = ?", stored,
				today().plusDays(expiresInDays), pass.getId());

		redeem(pass.getId(), student).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code));
		assertThat(redemptionsOf(pass)).isZero();
	}

	@Test
	void 만료일_당일은_쓸_수_있다() throws Exception {
		long student = register(NAME, PROVIDER, phone);
		jdbc.update("UPDATE dongtanpass_pass SET expires_on = ? WHERE id = ?", today(), pass.getId());

		redeem(pass.getId(), student).andExpect(status().isOk());
	}

	// 관리자가 다시 켜면 같은 요청이 지나간다. 이 거부는 멱등 응답으로 보관하지 않는다(같은 키로 다시 보내면 처리된다).
	@Test
	void 패스_할인이_꺼져_있으면_409이고_켜면_같은_키로_다시_보내_쓴다() throws Exception {
		long student = register(NAME, PROVIDER, phone);
		String key = UUID.randomUUID().toString();
		setDiscountActive(false);
		try {
			redeemWithKey(pass.getId(), student, key).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("DONGTANPASS_DISCOUNT_INACTIVE"));
			assertThat(statusOf(pass)).isEqualTo("ISSUED");
		}
		finally {
			setDiscountActive(true);
		}

		redeemWithKey(pass.getId(), student, key).andExpect(status().isOk())
			.andExpect(header().doesNotExist("Idempotency-Replayed"));
	}

	@Test
	void 없는_학생은_404다() throws Exception {
		redeem(pass.getId(), Long.MAX_VALUE).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_STUDENT_NOT_FOUND"));
	}

	// 관리자도 다른 지점 학생에게는 쓸 수 없다(쓰는 지점은 패스를 받는 지점이다).
	@Test
	void 패스를_받는_지점의_학생이_아니면_409다() throws Exception {
		long student = register(NAME, OTHER_CAMPUS, phone);

		redeem(pass.getId(), student).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_STUDENT_NOT_AT_PROVIDING_CAMPUS"));
		redeemAs(pass.getId(), student, UserRole.ADMIN, null).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_STUDENT_NOT_AT_PROVIDING_CAMPUS"));
	}

	@Test
	void 퇴원한_학생은_신규가_아니다() throws Exception {
		long student = register(NAME, PROVIDER, phone);
		jdbc.update("UPDATE core_student SET status = 'WITHDRAWN' WHERE id = ?", student);

		redeem(pass.getId(), student).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_STUDENT_NOT_NEW"));
	}

	// 자료를 만든 시각으로 본다(고칠 수 있는 등록일이 아니다). 7일째 되는 시각부터는 신규가 아니다.
	@Test
	void 학생_자료를_만든_지_7일이_지나면_신규가_아니다() throws Exception {
		long fresh = register(NAME, PROVIDER, phone);
		// 같은 이름·같은 학부모로는 두 번 등록되지 않는다. 신규 확인이 이름보다 먼저라 이름이 달라도 된다.
		long old = register("김하은", PROVIDER, newPhone());
		setCreatedAgo(fresh, Duration.ofDays(7).minusMinutes(1));
		setCreatedAgo(old, Duration.ofDays(7));

		redeem(pass.getId(), old).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_STUDENT_NOT_NEW"));
		redeem(pass.getId(), fresh).andExpect(status().isOk());
	}

	@Test
	void 이미_다른_패스를_쓴_학생은_신규가_아니다() throws Exception {
		long student = register(NAME, PROVIDER, phone);
		redeem(pass.getId(), student).andExpect(status().isOk());
		Pass second = save(NAME, phone);

		redeem(second.getId(), student).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_STUDENT_NOT_NEW"));
		assertThat(statusOf(second)).isEqualTo("ISSUED");
		// 유니크 키에 닿기 전에 확인한다. 이름이 다른 패스여도 "신규가 아님"이 먼저다.
		Pass otherName = save("김하은", phone);
		redeem(otherName.getId(), student).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_STUDENT_NOT_NEW"));
	}

	@Test
	void 이름이_다르면_409다() throws Exception {
		long student = register("김하은", PROVIDER, phone);

		redeem(pass.getId(), student).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_HOLDER_NAME_MISMATCH"));
	}

	@Test
	void 학부모_번호_중에_패스의_번호가_없으면_409다() throws Exception {
		long student = register(NAME, PROVIDER, newPhone(), newPhone());

		redeem(pass.getId(), student).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_HOLDER_PHONE_MISMATCH"));
	}

	// 학생 본인 번호가 패스의 번호여도 학부모 번호가 아니면 맞지 않는다.
	@Test
	void 학생_본인_번호는_학부모_번호가_아니다() throws Exception {
		long student = registerWithOwnPhone(NAME, phone, newPhone());

		redeem(pass.getId(), student).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_HOLDER_PHONE_MISMATCH"));
	}

	@Test
	void 이름과_번호가_모두_다르면_이름의_409다() throws Exception {
		long student = register("김하은", PROVIDER, newPhone());

		redeem(pass.getId(), student).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_HOLDER_NAME_MISMATCH"));
	}

	// 확인 순서: 신규가 아닌 것이 이름·번호보다 먼저다(다니던 학생에게 쓰려는 것을 먼저 알린다).
	@Test
	void 신규가_아니면서_이름도_다르면_신규의_409다() throws Exception {
		long student = register("김하은", PROVIDER, newPhone());
		jdbc.update("UPDATE core_student SET status = 'WITHDRAWN' WHERE id = ?", student);

		redeem(pass.getId(), student).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_STUDENT_NOT_NEW"));
	}

	// 응답을 받지 못한 화면이 같은 키로 다시 보내도 한 번만 처리하고 처음의 응답이 다시 나간다.
	@Test
	void 같은_키로_다시_보내면_처음의_응답이_다시_나가고_기록은_하나다() throws Exception {
		long student = register(NAME, PROVIDER, phone);
		String key = UUID.randomUUID().toString();

		String first = redeemWithKey(pass.getId(), student, key).andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		MvcResult again = redeemWithKey(pass.getId(), student, key).andExpect(status().isOk())
			.andExpect(header().string("Idempotency-Replayed", "true"))
			.andReturn();

		assertThat(again.getResponse().getContentAsString()).isEqualTo(first);
		assertThat(redemptionsOf(pass)).isOne();
	}

	@Test
	void 멱등_키가_없으면_400이다() throws Exception {
		long student = register(NAME, PROVIDER, phone);

		mockMvc.perform(post(path(pass.getId())).with(securityContext(login(UserRole.STAFF, PROVIDER)))
			.with(csrfToken(mockMvc))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body(student))).andExpect(status().isBadRequest());
		assertThat(statusOf(pass)).isEqualTo("ISSUED");
	}

	@ParameterizedTest
	@ValueSource(strings = { "{}", "{\"studentId\":\"12\"}", "{\"studentId\":1.5}", "{\"studentId\":1,\"memo\":\"x\"}" })
	void 학생_ID가_없거나_틀리거나_모르는_칸이_있으면_400이다(String json) throws Exception {
		send(pass.getId(), json, UserRole.STAFF, PROVIDER, UUID.randomUUID().toString())
			.andExpect(status().isBadRequest());
		assertThat(statusOf(pass)).isEqualTo("ISSUED");
	}

	@Test
	void 응답과_로그에_이름과_번호가_없다(CapturedOutput output) throws Exception {
		long student = register(NAME, PROVIDER, phone);
		Pass other = save(NAME, newPhone());

		String responses = redeem(other.getId(), student).andExpect(status().isConflict())
			.andReturn()
			.getResponse()
			.getContentAsString()
				+ redeem(pass.getId(), student).andExpect(status().isOk())
					.andReturn()
					.getResponse()
					.getContentAsString();

		assertThat(responses).doesNotContain(NAME).doesNotContain(phone.substring(3));
		assertThat(output.getAll()).doesNotContain(NAME).doesNotContain(phone.substring(3));
	}

	private ResultActions redeem(long passId, long studentId) throws Exception {
		return redeemWithKey(passId, studentId, UUID.randomUUID().toString());
	}

	private ResultActions redeemWithKey(long passId, long studentId, String key) throws Exception {
		return send(passId, body(studentId), UserRole.STAFF, PROVIDER, key);
	}

	private ResultActions redeemAs(long passId, long studentId, UserRole role, Long campusId) throws Exception {
		return send(passId, body(studentId), role, campusId, UUID.randomUUID().toString());
	}

	private ResultActions send(long passId, String json, UserRole role, Long campusId, String key) throws Exception {
		return mockMvc.perform(post(path(passId)).with(securityContext(login(role, campusId)))
			.with(csrfToken(mockMvc))
			.with(idempotencyKey(key))
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	// 데스크가 쓰는 등록 API로 등록한다. 학부모 번호는 순서대로 어머니, 아버지, …다.
	private long register(String name, long campusId, String... guardianPhones) throws Exception {
		String[] relations = { "MOTHER", "FATHER", "GRANDPARENT", "OTHER" };
		StringBuilder guardians = new StringBuilder();
		for (int i = 0; i < guardianPhones.length; i++) {
			guardians.append(i == 0 ? "" : ",")
				.append("{\"name\":\"보호자\",\"relation\":\"%s\",\"phone\":\"%s\"}".formatted(relations[i],
						guardianPhones[i]));
		}
		return studentIdOf(sendRegistration("""
				{"campusId": %d, "name": "%s", "grade": "HIGH_1", "guardians": [%s]}
				""".formatted(campusId, name, guardians)));
	}

	private long registerWithOwnPhone(String name, String ownPhone, String guardianPhone) throws Exception {
		return studentIdOf(sendRegistration("""
				{"campusId": 1, "name": "%s", "grade": "HIGH_1", "phone": "%s",
				 "guardians": [{"name": "보호자", "relation": "MOTHER", "phone": "%s"}]}
				""".formatted(name, ownPhone, guardianPhone)));
	}

	private ResultActions sendRegistration(String json) throws Exception {
		MockHttpServletRequestBuilder request = post("/api/v1/students")
			.with(securityContext(login(UserRole.STAFF, PROVIDER)))
			.with(csrfToken(mockMvc))
			.with(idempotencyKey())
			.contentType(MediaType.APPLICATION_JSON)
			.content(json);
		return mockMvc.perform(request).andExpect(status().isCreated());
	}

	private static long studentIdOf(ResultActions registered) throws Exception {
		String json = registered.andReturn().getResponse().getContentAsString();
		return Long.parseLong(json.replaceAll("(?s).*?\"id\"\\s*:\\s*(\\d+).*", "$1"));
	}

	private SecurityContext login(UserRole role, Long campusId) {
		AuthenticatedUser user = new AuthenticatedUser(staff, "desk-staff", "데스크", role, campusId,
				role == UserRole.PARTNER ? PARTNER : null);
		SecurityContext login = SecurityContextHolder.createEmptyContext();
		login.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null,
				List.of(new SimpleGrantedAuthority(role.authority()))));
		return login;
	}

	private void setDiscountActive(boolean active) {
		jdbc.update("UPDATE core_discount_type SET active = ? WHERE code = 'DONGTAN_PASS'", active);
	}

	private void setCreatedAgo(long studentId, Duration ago) {
		// DB에는 한국 시각으로 적혀 있다(ADR-0017).
		jdbc.update("UPDATE core_student SET created_at = ? WHERE id = ?",
				LocalDateTime.ofInstant(clock.instant().minus(ago), SEOUL), studentId);
	}

	private String statusOf(Pass target) {
		return jdbc.queryForObject("SELECT status FROM dongtanpass_pass WHERE id = ?", String.class, target.getId());
	}

	private int redemptionsOf(Pass target) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM dongtanpass_redemption WHERE pass_id = ?", Integer.class,
				target.getId());
	}

	private List<Map<String, Object>> auditOf(Pass target) {
		return jdbc.queryForList(
				"SELECT * FROM core_audit_log WHERE action = 'dongtanpass.pass-redeemed' AND target_type = 'pass' AND target_id = ?",
				target.getId());
	}

	private static String path(long passId) {
		return "/api/v1/dongtanpass/passes/" + passId + "/redeem";
	}

	private static String body(long studentId) {
		return "{\"studentId\":" + studentId + "}";
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
		return 9_017_000_000L + RANDOM.nextInt(1_000_000_000);
	}

}
