package com.jey.core.member.web;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.member.StudentService;
import com.jey.core.member.StudentView;
import com.jey.core.shared.PhoneNumber;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static com.jey.TestCsrf.csrfToken;
import static com.jey.core.member.MemberFixtures.DONGTAN;
import static com.jey.core.member.MemberFixtures.mother;
import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.core.member.MemberFixtures.registration;
import static com.jey.core.member.MemberFixtures.registrationWithPhone;
import static com.jey.core.member.MemberFixtures.uniqueName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 요청 검증이 놓치면 엔티티의 예외가 500으로 나간다. 검증 애너테이션 하나하나가 400과 500을 가른다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class MemberApiEdgeTest {

	private static final String STUDENTS = "/api/v1/students";

	private static final long STAFF_ACCOUNT = 4242;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	StudentService studentService;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 학부모는_한_번에_네_명까지_적는다() throws Exception {
		send(post(STUDENTS), createBody(uniqueName(), guardians(4))).andExpect(status().isCreated())
				.andExpect(jsonPath("$.guardians.length()").value(4));
		send(post(STUDENTS), createBody(uniqueName(), guardians(5))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[?(@.field == 'guardians')]").isNotEmpty());
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 학부모_목록에_빈_원소가_있으면_400이다() throws Exception {
		send(post(STUDENTS), createBody(uniqueName(), "null")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 학부모_추가도_값을_검증한다() throws Exception {
		StudentView student = studentService.register(registration(uniqueName(), mother(randomPhone())));

		send(post(STUDENTS + "/{id}/guardians", student.id()), "{}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[?(@.field == 'name')]").isNotEmpty())
				.andExpect(jsonPath("$.errors[?(@.field == 'relation')]").isNotEmpty())
				.andExpect(jsonPath("$.errors[?(@.field == 'phone')]").isNotEmpty());
		send(post(STUDENTS + "/{id}/guardians", student.id()), """
				{"name": "%s", "relation": "FATHER", "phone": "%s"}
				""".formatted("가".repeat(51), randomPhone().value())).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field == 'name')]").isNotEmpty());
		send(post(STUDENTS + "/{id}/guardians", student.id()), """
				{"name": "　", "relation": "FATHER", "phone": "%s"}
				""".formatted(randomPhone().value())).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field == 'name')]").isNotEmpty());

		assertThat(studentService.get(student.id()).guardians()).hasSize(1);
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 수정할_이름이나_학교가_길면_400이다() throws Exception {
		StudentView student = studentService.register(registration(uniqueName(), mother(randomPhone())));
		long guardianId = student.guardians().getFirst().id();

		send(put(STUDENTS + "/{id}", student.id()), updateBody("가".repeat(51), "동탄고"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field == 'name')]").isNotEmpty());
		send(put(STUDENTS + "/{id}", student.id()), updateBody("　", "동탄고")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field == 'name')]").isNotEmpty());
		send(put(STUDENTS + "/{id}", student.id()), updateBody(student.name(), "나".repeat(51)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field == 'school')]").isNotEmpty());
		send(put("/api/v1/guardians/{id}", guardianId), "{\"name\": \"%s\"}".formatted("가".repeat(51)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field == 'name')]").isNotEmpty());

		assertThat(studentService.get(student.id()).name()).isEqualTo(student.name());
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 마지막_연락처인_본인_번호는_지울_수_없다() throws Exception {
		StudentView student = studentService.register(registrationWithPhone(uniqueName(), randomPhone()));

		mockMvc.perform(delete(STUDENTS + "/{id}/phone", student.id()).with(csrfToken(mockMvc)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("MEMBER_LAST_CONTACT"));
	}

	// 키 설정이 틀렸거나 저장된 값이 깨졌을 때다. "연락처 없음"으로 조용히 보이면 사고를 알 수 없다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 저장된_전화번호를_풀지_못하면_빈_값이_아니라_500이다() throws Exception {
		String name = uniqueName();
		PhoneNumber phone = randomPhone();
		StudentView student = studentService.register(registration(name, mother(phone)));
		long guardianId = student.guardians().getFirst().id();
		String original = jdbc.queryForObject("SELECT phone_enc FROM core_guardian WHERE id = ?", String.class,
				guardianId);
		jdbc.update("UPDATE core_guardian SET phone_enc = ? WHERE id = ?", "AAAA" + original.substring(4), guardianId);
		try {
			String detail = mockMvc.perform(get(STUDENTS + "/{id}", student.id()))
					.andExpect(status().isInternalServerError())
					.andExpect(jsonPath("$.code").value("COMMON_INTERNAL_ERROR"))
					.andReturn()
					.getResponse()
					.getContentAsString();
			String list = mockMvc.perform(get(STUDENTS).param("name", name))
					.andExpect(status().isInternalServerError())
					.andExpect(jsonPath("$.code").value("COMMON_INTERNAL_ERROR"))
					.andReturn()
					.getResponse()
					.getContentAsString();

			assertThat(detail + list).doesNotContain(original.substring(4, 24))
					.doesNotContain(phone.value().substring(3))
					.doesNotContain(phone.last4());
		}
		finally {
			// 다른 테스트의 목록 조회에 이 행이 끼지 않게 되돌린다.
			jdbc.update("UPDATE core_guardian SET phone_enc = ? WHERE id = ?", original, guardianId);
		}
		mockMvc.perform(get(STUDENTS + "/{id}", student.id())).andExpect(status().isOk());
	}

	// 가린 번호의 가운데 자리는 "그 번호가 이미 있다"는 응답을 되풀이해 받으면 알아낼 수 있다.
	// 막을 수는 없어서, 누가 그런 응답을 받았는지 남긴다. 번호는 남기지 않는다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = STAFF_ACCOUNT)
	void 번호가_이미_있다고_알려_준_응답은_계정과_함께_기록된다(CapturedOutput output) throws Exception {
		PhoneNumber othersPhone = randomPhone();
		PhoneNumber newPhone = randomPhone();
		long othersGuardian = studentService.register(registration(uniqueName(), mother(othersPhone)))
				.guardians()
				.getFirst()
				.id();
		StudentView student = studentService.register(registration(uniqueName(), mother(randomPhone())));
		long guardianId = student.guardians().getFirst().id();

		send(put("/api/v1/guardians/{id}", guardianId), "{\"name\": \"김어머니\", \"phone\": \"%s\"}"
				.formatted(othersPhone.value())).andExpect(status().isConflict());
		send(put("/api/v1/guardians/{id}", guardianId), "{\"name\": \"김어머니\", \"phone\": \"%s\"}"
				.formatted(newPhone.value())).andExpect(status().isOk());
		send(post(STUDENTS + "/{id}/guardians", student.id()), guardianBody(othersPhone))
				.andExpect(status().isCreated());
		send(post(STUDENTS + "/{id}/guardians", student.id()), guardianBody(othersPhone))
				.andExpect(status().isConflict());
		// 이름만 바꾼 수정은 번호와 무관하다.
		send(put("/api/v1/guardians/{id}", guardianId), "{\"name\": \"박어머니\"}").andExpect(status().isOk());

		String who = " account=" + STAFF_ACCOUNT + " ";
		assertThat(output.getAll())
				.contains("event=GUARDIAN_PHONE_DUPLICATED" + who + "student=null guardian=" + guardianId)
				.contains("event=GUARDIAN_PHONE_CHANGED" + who + "student=null guardian=" + guardianId)
				.contains("event=LINKED_TO_EXISTING" + who + "student=" + student.id() + " guardian=" + othersGuardian)
				.contains("event=GUARDIAN_ALREADY_LINKED" + who + "student=" + student.id() + " guardian="
						+ othersGuardian);
		assertThat(output.getAll().split("event=GUARDIAN_PHONE_CHANGED", -1)).hasSize(2);
		assertThat(output.getAll()).doesNotContain(othersPhone.value())
				.doesNotContain(othersPhone.value().substring(3))
				.doesNotContain(newPhone.value().substring(3));
	}

	private ResultActions send(MockHttpServletRequestBuilder request, String json) throws Exception {
		return mockMvc.perform(request.with(csrfToken(mockMvc)).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private static String createBody(String name, String guardiansJson) {
		return "{\"campusId\": 1, \"name\": \"%s\", \"grade\": \"HIGH_1\", \"guardians\": [%s]}".formatted(name,
				guardiansJson);
	}

	private static String guardians(int count) {
		return IntStream.range(0, count).mapToObj(i -> guardianBody(randomPhone())).collect(Collectors.joining(", "));
	}

	private static String guardianBody(PhoneNumber phone) {
		return "{\"name\": \"김보호자\", \"relation\": \"OTHER\", \"phone\": \"%s\"}".formatted(phone.value());
	}

	private static String updateBody(String name, String school) {
		return """
				{"campusId": 1, "name": "%s", "school": "%s", "grade": "HIGH_1", "status": "ENROLLED",
				 "registeredAt": "2026-03-02"}
				""".formatted(name, school);
	}

}
