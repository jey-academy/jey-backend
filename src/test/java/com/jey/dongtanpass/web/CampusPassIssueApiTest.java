package com.jey.dongtanpass.web;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.member.StudentRegistration;
import com.jey.core.member.StudentService;
import com.jey.core.member.domain.Grade;
import com.jey.core.shared.PhoneNumber;
import com.jey.core.shared.PhoneProtector;
import com.jey.dongtanpass.application.ConsentNotice;
import com.jey.dongtanpass.domain.LinkToken;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static com.jey.TestCsrf.csrfToken;
import static com.jey.TestIdempotency.idempotencyKey;
import static com.jey.core.member.MemberFixtures.mother;
import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.core.member.MemberFixtures.registration;
import static com.jey.core.member.MemberFixtures.registrationWithPhone;
import static com.jey.core.member.MemberFixtures.removeAllContacts;
import static com.jey.core.member.MemberFixtures.uniqueName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 관리자가 동탄관에 다니는 학생에게 패스를 발급한다(연계 학원에서 쓴다). 월 결제마다 받을 수 있어 같은 학생이 여러 장 받는다.
// 학생 이름과 학부모 번호는 학생 자료에서 가져오고, 할인율은 발급 때의 설정을 패스에 남긴다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class CampusPassIssueApiTest {

	private static final String PASSES = "/api/v1/dongtanpass/campus-passes";

	// 패스 제도에 참여하는 지점(dongtanpass_benefit의 받는 지점)이 발급 지점이다.
	private static final long PROVIDER = 1L;

	private static final long OTHER_CAMPUS = 2L;

	private static final long ADMIN = 900_601L;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	StudentService students;

	@Autowired
	PhoneProtector phoneProtector;

	@Autowired
	Clock clock;

	@AfterEach
	void restoreRate() {
		jdbc.update("UPDATE dongtanpass_campus_benefit SET discount_rate = 0.0500");
	}

	@Test
	@WithJeyUser(id = ADMIN, role = UserRole.ADMIN)
	void 관리자가_학생에게_발급하면_201과_패스_번호_링크_할인율을_준다() throws Exception {
		PhoneNumber phone = randomPhone();
		String name = uniqueName();
		long student = students.register(registration(name, mother(phone))).id();

		String response = issue(student).andExpect(status().isCreated())
			.andExpect(jsonPath("$.passNo").value(matchesPattern("DT-[A-Z2-9]{6}")))
			.andExpect(jsonPath("$.linkToken").isString())
			.andExpect(jsonPath("$.expiresOn").value(LocalDate.now(clock).plusMonths(1).toString()))
			.andExpect(jsonPath("$.discountRate").value(0.05))
			.andReturn()
			.getResponse()
			.getContentAsString();

		Map<String, Object> row = onlyPassOf(student);
		// 응답의 토큰이 실제 학부모 링크다(해시가 저장된 값과 같고, 그 토큰으로 학부모 화면이 열린다).
		String linkToken = JsonPath.read(response, "$.linkToken");
		assertThat(row).containsEntry("pass_no", JsonPath.read(response, "$.passNo"))
			.containsEntry("link_token_hash", LinkToken.hashOf(linkToken));
		mockMvc.perform(post("/api/v1/dongtanpass/holder/view").with(csrfToken(mockMvc))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\":\"" + linkToken + "\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.direction").value("CAMPUS_ISSUED"));
		assertThat(row).containsEntry("direction", "CAMPUS_ISSUED")
			.containsEntry("issuer_campus_id", PROVIDER)
			.containsEntry("student_name", name)
			.containsEntry("guardian_phone_bidx", phoneProtector.fullIndex(phone))
			.containsEntry("payment_method", "CARD");
		assertThat(row.get("partner_id")).isNull();
		assertThat(row.get("discount_rate").toString()).isEqualTo("0.0500");
		// 동의는 JEY 관리자가 고지하고 확인한 것으로 남는다(연계 학원이 확인한 것과 가린다).
		assertThat(jdbc.queryForMap("SELECT notice_version, channel FROM dongtanpass_consent WHERE pass_id = ?",
				row.get("id")))
			.containsEntry("notice_version", ConsentNotice.CAMPUS_ISSUED.version())
			.containsEntry("channel", "ADMIN_CONFIRMED");
	}

	// 이벤트를 받은 감사 리스너가 남긴다(커밋 뒤라 기다린다). 봉투의 지점은 발급 지점이다.
	@Test
	@WithJeyUser(id = ADMIN, role = UserRole.ADMIN)
	void 발급은_감사_로그에_campus_pass_issued로_남는다() throws Exception {
		long student = students.register(registration(uniqueName(), mother(randomPhone()))).id();
		issue(student).andExpect(status().isCreated());
		Object passId = onlyPassOf(student).get("id");

		await().atMost(Duration.ofSeconds(10))
			.untilAsserted(() -> assertThat(jdbc.queryForList("SELECT actor_id, campus_id FROM core_audit_log"
					+ " WHERE action = 'dongtanpass.campus-pass-issued' AND target_type = 'pass' AND target_id = ?",
					passId))
				.singleElement()
				.satisfies(audit -> assertThat(audit).containsEntry("actor_id", ADMIN)
					.containsEntry("campus_id", PROVIDER)));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 같은_학생에게_여러_번_발급할_수_있다() throws Exception {
		long student = students.register(registration(uniqueName(), mother(randomPhone()))).id();

		issue(student).andExpect(status().isCreated());
		issue(student).andExpect(status().isCreated());

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dongtanpass_pass WHERE student_id = ?", Long.class,
				student))
			.isEqualTo(2);
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 할인율을_바꾼_뒤의_발급은_새_값을_남긴다() throws Exception {
		long student = students.register(registration(uniqueName(), mother(randomPhone()))).id();
		jdbc.update("UPDATE dongtanpass_campus_benefit SET discount_rate = 0.0700");

		issue(student).andExpect(status().isCreated()).andExpect(jsonPath("$.discountRate").value(0.07));

		assertThat(onlyPassOf(student).get("discount_rate").toString()).isEqualTo("0.0700");
	}

	// 학부모가 없으면 학생 본인 번호가 대표 연락처다(PrimaryContactRule). 그 번호로 발급한다.
	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 학부모가_없으면_학생_본인_번호로_발급한다() throws Exception {
		PhoneNumber own = randomPhone();
		long student = students.register(registrationWithPhone(uniqueName(), own, mother(randomPhone()))).id();
		jdbc.update("DELETE FROM core_student_guardian WHERE student_id = ?", student);

		issue(student).andExpect(status().isCreated());

		assertThat(onlyPassOf(student)).containsEntry("guardian_phone_bidx", phoneProtector.fullIndex(own));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 없는_학생은_404() throws Exception {
		issue(Long.MAX_VALUE).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_STUDENT_NOT_FOUND"));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 재원_중이_아닌_학생은_409() throws Exception {
		long student = students.register(registration(uniqueName(), mother(randomPhone()))).id();
		jdbc.update("UPDATE core_student SET status = 'WITHDRAWN' WHERE id = ?", student);

		issue(student).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_STUDENT_NOT_ENROLLED"));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 발급_지점의_학생이_아니면_409() throws Exception {
		long student = students.register(new StudentRegistration(OTHER_CAMPUS, null, uniqueName(), "동탄고",
				Grade.HIGH_1, null, null, List.of(mother(randomPhone()))))
			.id();

		issue(student).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_STUDENT_NOT_AT_ISSUING_CAMPUS"));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 연락처가_하나도_없으면_409() throws Exception {
		long student = students.register(registration(uniqueName(), mother(randomPhone()))).id();
		removeAllContacts(jdbc, student);

		issue(student).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_STUDENT_HAS_NO_CONTACT"));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dongtanpass_pass WHERE student_id = ?", Long.class,
				student))
			.isZero();
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 동의와_고지_버전과_결제를_연계_학원_발급처럼_본다() throws Exception {
		long student = students.register(registration(uniqueName(), mother(randomPhone()))).id();
		String today = LocalDate.now(clock).toString();
		String tomorrow = LocalDate.now(clock).plusDays(1).toString();
		String campusVersion = ConsentNotice.CAMPUS_ISSUED.version();

		send(body(student, today, "280000", false, campusVersion)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_CONSENT_NOT_CONFIRMED"));
		// 연계 학원 발급의 문구로 받은 동의는 받지 않는다.
		send(body(student, today, "280000", true, ConsentNotice.CURRENT.version())).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_NOTICE_VERSION_MISMATCH"));
		send(body(student, tomorrow, "280000", true, campusVersion)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PAID_ON_IN_FUTURE"));
		send(body(student, today, "0", true, campusVersion)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_INVALID_PAID_AMOUNT"));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dongtanpass_pass WHERE student_id = ?", Long.class,
				student))
			.isZero();
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = PROVIDER)
	void 직원은_발급하지_못한다() throws Exception {
		issue(1L).andExpect(status().isForbidden());
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = 1)
	void 연계_학원은_JEY_패스를_발급하지_못한다() throws Exception {
		issue(1L).andExpect(status().isForbidden());
	}

	// 연락처가 없어 막힌 것은 고치면 같은 요청이 지나간다. 같은 멱등 키로 다시 보내도 막힌 응답을 돌려주지 않는다.
	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 연락처를_더한_뒤_같은_키로_다시_보내면_발급된다() throws Exception {
		long student = students.register(registration(uniqueName(), mother(randomPhone()))).id();
		removeAllContacts(jdbc, student);
		String key = UUID.randomUUID().toString();

		sendWithKey(key, body(student, LocalDate.now(clock).toString(), "280000", true,
				ConsentNotice.CAMPUS_ISSUED.version()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_STUDENT_HAS_NO_CONTACT"));
		students.addGuardian(student, mother(randomPhone()));

		sendWithKey(key, body(student, LocalDate.now(clock).toString(), "280000", true,
				ConsentNotice.CAMPUS_ISSUED.version()))
			.andExpect(status().isCreated());
	}

	// 같은 학생에게 여러 장을 허용하므로, 두 번 눌러 두 장이 생기는 것을 막는 것은 멱등 키뿐이다.
	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 같은_키로_다시_보내면_한_장만_생기고_처음_응답이_다시_나간다() throws Exception {
		long student = students.register(registration(uniqueName(), mother(randomPhone()))).id();
		String key = UUID.randomUUID().toString();
		String json = body(student, LocalDate.now(clock).toString(), "280000", true,
				ConsentNotice.CAMPUS_ISSUED.version());

		String first = sendWithKey(key, json).andExpect(status().isCreated()).andReturn().getResponse()
			.getContentAsString();
		String second = sendWithKey(key, json).andExpect(status().isCreated()).andReturn().getResponse()
			.getContentAsString();

		assertThat(second).isEqualTo(first);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dongtanpass_pass WHERE student_id = ?", Long.class,
				student))
			.isEqualTo(1);
	}

	// 링크 토큰 원문과 학생 이름은 로그에 남지 않는다(발급과 장부 조회 모두).
	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 로그에_토큰_원문과_학생_이름이_없다(CapturedOutput output) throws Exception {
		String name = uniqueName();
		long student = students.register(registration(name, mother(randomPhone()))).id();

		String token = JsonPath.read(issue(student).andExpect(status().isCreated()).andReturn().getResponse()
			.getContentAsString(), "$.linkToken");
		mockMvc.perform(get(PASSES).param("studentId", String.valueOf(student))).andExpect(status().isOk());

		assertThat(output).doesNotContain(token).doesNotContain(name);
	}

	private ResultActions sendWithKey(String key, String json) throws Exception {
		return mockMvc.perform(post(PASSES).with(csrfToken(mockMvc))
			.header("Idempotency-Key", key)
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	private ResultActions issue(long studentId) throws Exception {
		return send(body(studentId, LocalDate.now(clock).toString(), "280000", true,
				ConsentNotice.CAMPUS_ISSUED.version()));
	}

	private static String body(long studentId, String paidOn, String paidAmount, boolean consent, String version) {
		return """
				{"studentId": %d, "paidOn": "%s", "paidAmount": %s, "paymentMethod": "CARD",
				 "consentConfirmed": %s, "noticeVersion": "%s"}
				""".formatted(studentId, paidOn, paidAmount, consent, version);
	}

	private ResultActions send(String json) throws Exception {
		return mockMvc.perform(post(PASSES).with(csrfToken(mockMvc))
			.with(idempotencyKey())
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	private Map<String, Object> onlyPassOf(long student) {
		return jdbc.queryForMap("SELECT * FROM dongtanpass_pass WHERE student_id = ?", student);
	}

}
