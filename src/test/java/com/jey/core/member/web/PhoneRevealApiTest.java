package com.jey.core.member.web;

import java.util.List;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.audit.AuditLogService;
import com.jey.core.audit.api.AuditOutcome;
import com.jey.core.audit.domain.AuditRecord;
import com.jey.core.auth.api.UserRole;
import com.jey.core.member.StudentService;
import com.jey.core.member.StudentView;
import com.jey.core.shared.PhoneNumber;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static com.jey.TestCsrf.csrfToken;
import static com.jey.core.member.MemberFixtures.DONGTAN;
import static com.jey.core.member.MemberFixtures.mother;
import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.core.member.MemberFixtures.registration;
import static com.jey.core.member.MemberFixtures.registrationWithPhone;
import static com.jey.core.member.MemberFixtures.uniqueName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 전체 번호는 이 경로로만 나간다. 볼 때마다 누가 누구의 번호를 봤는지 남고, 계정마다 볼 수 있는 횟수에 한도가 있다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PhoneRevealApiTest {

	// 테스트마다 다른 계정을 쓴다. 그 계정의 횟수와 기록을 통째로 센다.
	private static final long VIEWER = 9101;

	private static final long HEAVY_VIEWER = 9102;

	private static final long OTHER_VIEWER = 9103;

	private static final long MISSING_VIEWER = 9104;

	private static final long ADMIN = 9105;

	private static final int LIMIT = 60;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	StudentService studentService;

	@Autowired
	AuditLogService auditLogService;

	@Autowired
	StringRedisTemplate redis;

	@BeforeEach
	void forgetCounts() {
		redis.delete(redis.keys("jey:auth:account-limit:member.phone-reveal:*"));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = VIEWER)
	void 직원은_학생과_학부모의_전체_번호를_보고_본_사실이_남는다() throws Exception {
		PhoneNumber studentPhone = randomPhone();
		PhoneNumber motherPhone = randomPhone();
		StudentView student = studentService.register(registrationWithPhone(uniqueName(), studentPhone, mother(motherPhone)));
		long guardianId = student.guardians().getFirst().id();

		revealStudent(student.id()).andExpect(status().isOk())
				.andExpect(jsonPath("$.phone").value(hyphenated(studentPhone)))
				// 전체 번호가 브라우저나 중간 서버에 남으면 안 된다.
				.andExpect(header().string("Cache-Control", containsString("no-store")));
		revealGuardian(student.id(), guardianId).andExpect(status().isOk())
				.andExpect(jsonPath("$.phone").value(hyphenated(motherPhone)))
				.andExpect(header().string("Cache-Control", containsString("no-store")));
		revealGuardian(student.id(), guardianId).andExpect(status().isOk());

		List<AuditRecord> seen = auditLogService.search(VIEWER, null, AuditOutcome.DONE, null, null, null, null, 0, 20)
				.getContent()
				.stream()
				.filter(each -> each.getAction().endsWith("-phone-revealed"))
				.toList();
		// 볼 때마다 한 줄이다. 같은 번호를 두 번 보면 두 줄이다.
		// 학부모는 지점이 없지만, 그 학생의 지점으로 적는다. 지점으로 걸러 보는 조회에서 빠지지 않아야 한다.
		assertThat(seen)
				.extracting(AuditRecord::getAction, AuditRecord::getTargetType, AuditRecord::getTargetId,
						AuditRecord::getCampusId)
				.containsExactly(tuple("member.guardian-phone-revealed", "guardian", guardianId, DONGTAN),
						tuple("member.guardian-phone-revealed", "guardian", guardianId, DONGTAN),
						tuple("member.student-phone-revealed", "student", student.id(), DONGTAN));
		assertThat(auditLogService.detailOf(seen.getFirst())).containsEntry("studentId", (int) student.id());
		// 기록에는 번호가 없다.
		assertThat(seen).allSatisfy(each -> assertThat(String.valueOf(each.getDetailJson()))
				.doesNotContain(studentPhone.value().substring(3))
				.doesNotContain(motherPhone.value().substring(3)));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN, id = ADMIN)
	void 관리자도_본다() throws Exception {
		PhoneNumber motherPhone = randomPhone();
		StudentView student = studentService.register(registration(uniqueName(), mother(motherPhone)));

		revealGuardian(student.id(), student.guardians().getFirst().id()).andExpect(status().isOk())
				.andExpect(jsonPath("$.phone").value(hyphenated(motherPhone)));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = MISSING_VIEWER)
	void 없는_대상의_번호는_볼_수_없다() throws Exception {
		StudentView student = studentService.register(registration(uniqueName(), mother(randomPhone())));
		StudentView other = studentService.register(registration(uniqueName(), mother(randomPhone())));
		long othersGuardianId = other.guardians().getFirst().id();

		revealStudent(Long.MAX_VALUE).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("MEMBER_STUDENT_NOT_FOUND"));
		// 본인 번호가 없는 학생이다.
		revealStudent(student.id()).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("MEMBER_PHONE_NOT_FOUND"));
		revealGuardian(Long.MAX_VALUE, othersGuardianId).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("MEMBER_STUDENT_NOT_FOUND"));
		// 있는 학부모지만 이 학생에게 연결되지 않았다. 학부모 ID만 바꿔 가며 남의 번호를 볼 수 없다.
		revealGuardian(student.id(), othersGuardianId).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("MEMBER_GUARDIAN_NOT_FOUND"));

		// 보지 못한 것은 "봤다"로 남지 않는다.
		assertThat(auditLogService.search(MISSING_VIEWER, null, null, null, null, null, null, 0, 20)
				.getContent()).extracting(AuditRecord::getAction).doesNotContain("member.student-phone-revealed",
						"member.guardian-phone-revealed");
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = HEAVY_VIEWER)
	void 한도를_넘으면_429로_막고_막힌_사실은_한_번만_남긴다() throws Exception {
		PhoneNumber motherPhone = randomPhone();
		StudentView student = studentService.register(registration(uniqueName(), mother(motherPhone)));
		long guardianId = student.guardians().getFirst().id();
		for (int i = 0; i < LIMIT; i++) {
			revealGuardian(student.id(), guardianId).andExpect(status().isOk());
		}

		for (int i = 0; i < 5; i++) {
			revealGuardian(student.id(), guardianId).andExpect(status().isTooManyRequests())
					.andExpect(jsonPath("$.code").value("MEMBER_PHONE_REVEAL_LIMITED"))
					.andExpect(jsonPath("$.phone").doesNotExist())
					// 한 시간 구간의 남은 시간(초). 방금 시작한 구간이라 거의 다 남아 있다.
					.andExpect(header().string("Retry-After", matchesPattern("(3[45]\\d\\d|3600)")));
		}

		assertThat(auditLogService.search(HEAVY_VIEWER, "member.guardian-phone-revealed", null, null, null, null,
				null, 0, 1).getTotalElements()).isEqualTo(LIMIT);
		// 막힌 계정이 계속 눌러도 기록은 구간마다 한 줄이다.
		List<AuditRecord> limited = auditLogService
				.search(HEAVY_VIEWER, "member.phone-reveal-limited", null, null, null, null, null, 0, 20)
				.getContent();
		assertThat(limited).hasSize(1);
		assertThat(limited.getFirst().getOutcome()).isEqualTo(AuditOutcome.DENIED);
		assertThat(limited.getFirst().getTargetType()).isNull();
	}

	// 없는 대상을 찌른 요청도 한 번으로 센다. 세지 않으면 ID를 훑어 보는 요청이 한도 없이 나간다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = OTHER_VIEWER)
	void 없는_대상을_찌른_요청도_횟수에_든다() throws Exception {
		for (int i = 0; i < LIMIT; i++) {
			revealStudent(Long.MAX_VALUE).andExpect(status().isNotFound());
		}

		revealStudent(Long.MAX_VALUE).andExpect(status().isTooManyRequests());
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = 1)
	void 연계_학원은_볼_수_없다() throws Exception {
		revealStudent(1).andExpect(status().isForbidden());
		revealGuardian(1, 1).andExpect(status().isForbidden());
	}

	@Test
	void 로그인하지_않으면_볼_수_없다() throws Exception {
		revealStudent(1).andExpect(status().isUnauthorized());
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = VIEWER)
	void CSRF_토큰이_없으면_볼_수_없다() throws Exception {
		mockMvc.perform(post("/api/v1/students/{id}/phone/reveal", 1)).andExpect(status().isForbidden());
	}

	// 주소만으로 열리는 GET이면 브라우저가 미리 읽거나 다른 사이트가 링크로 부를 수 있다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = VIEWER)
	void GET으로는_볼_수_없다() throws Exception {
		mockMvc.perform(get("/api/v1/students/{id}/phone/reveal", 1)).andExpect(status().is4xxClientError())
				.andExpect(jsonPath("$.phone").doesNotExist());
	}

	private ResultActions revealStudent(long studentId) throws Exception {
		return mockMvc.perform(post("/api/v1/students/{id}/phone/reveal", studentId).with(csrfToken(mockMvc)));
	}

	private ResultActions revealGuardian(long studentId, long guardianId) throws Exception {
		return mockMvc.perform(post("/api/v1/students/{id}/guardians/{guardianId}/phone/reveal", studentId, guardianId)
				.with(csrfToken(mockMvc)));
	}

	private static String hyphenated(PhoneNumber phone) {
		String digits = phone.value();
		return digits.substring(0, 3) + "-" + digits.substring(3, digits.length() - 4) + "-"
				+ digits.substring(digits.length() - 4);
	}

}
