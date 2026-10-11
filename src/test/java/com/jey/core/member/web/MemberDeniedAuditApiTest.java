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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static com.jey.TestCsrf.csrfToken;
import static com.jey.TestIdempotency.idempotencyKey;
import static com.jey.core.member.MemberFixtures.DONGTAN;
import static com.jey.core.member.MemberFixtures.mother;
import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.core.member.MemberFixtures.registration;
import static com.jey.core.member.MemberFixtures.uniqueName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 서비스만 불러 보는 테스트는 실제 요청에서 로그인 정보가 기록까지 따라오는지를 보지 못한다.
// 가린 전화번호를 대입으로 알아내려는 요청 셋을 실제 요청으로 보내고, 그 계정의 거부 기록으로 남는지 본다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MemberDeniedAuditApiTest {

	// 이 테스트만 쓰는 계정. 이 계정의 거부 기록을 통째로 센다.
	private static final long STAFF = 8181;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	StudentService studentService;

	@Autowired
	AuditLogService auditLogService;

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = STAFF)
	void 번호가_이미_있다고_답한_요청은_그_계정의_거부_기록으로_남는다() throws Exception {
		PhoneNumber phone = randomPhone();
		PhoneNumber othersPhone = randomPhone();
		String name = uniqueName();
		StudentView student = studentService.register(registration(name, mother(phone)));
		long guardianId = student.guardians().getFirst().id();
		studentService.register(registration(uniqueName(), mother(othersPhone)));

		send(post("/api/v1/students"), """
				{"campusId": 2, "name": "%s", "grade": "HIGH_1",
				 "guardians": [{"name": "김어머니", "relation": "MOTHER", "phone": "%s"}]}
				""".formatted(name, phone.value())).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("MEMBER_STUDENT_DUPLICATED"));
		send(put("/api/v1/guardians/{id}", guardianId),
				"{\"name\": \"김어머니\", \"phone\": \"%s\"}".formatted(othersPhone.value()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("MEMBER_GUARDIAN_PHONE_DUPLICATED"));
		send(post("/api/v1/students/{id}/guardians", student.id()),
				"{\"name\": \"김어머니\", \"relation\": \"FATHER\", \"phone\": \"%s\"}".formatted(phone.value()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("MEMBER_GUARDIAN_ALREADY_LINKED"));

		// 관리자가 "이 계정이 거부당한 것"을 찾을 때 쓰는 조건 그대로다.
		List<AuditRecord> denied = auditLogService.search(STAFF, null, AuditOutcome.DENIED, null, null, null, null, 0, 20)
				.getContent();
		assertThat(denied).extracting(AuditRecord::getAction, AuditRecord::getTargetType, AuditRecord::getTargetId,
				AuditRecord::getCampusId)
				.containsExactly(tuple("member.guardian-already-linked", "student", student.id(), null),
						tuple("member.guardian-phone-duplicated", "guardian", guardianId, null),
						tuple("member.student-duplicated", null, null, 2L));
		assertThat(denied).allSatisfy(each -> assertThat(String.valueOf(each.getDetailJson()))
				.doesNotContain(phone.value().substring(3))
				.doesNotContain(othersPhone.value().substring(3))
				.doesNotContain(name));
		// 거부된 요청은 "했다"로 남지 않는다. 이 계정이 한 일은 준비하면서 한 등록 둘의 것뿐이다.
		assertThat(auditLogService.search(STAFF, "member.guardian-updated", null, null, null, null, null, 0, 20))
				.isEmpty();
		assertThat(auditLogService.search(STAFF, "member.guardian-linked", null, null, null, null, null, 0, 20))
				.hasSize(2);
	}

	private ResultActions send(MockHttpServletRequestBuilder request, String json) throws Exception {
		return mockMvc.perform(request.with(csrfToken(mockMvc)).with(idempotencyKey()).contentType(MediaType.APPLICATION_JSON).content(json));
	}

}
