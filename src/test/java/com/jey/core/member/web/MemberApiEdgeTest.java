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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
import static com.jey.core.member.MemberFixtures.removeAllContacts;
import static com.jey.core.member.MemberFixtures.registrationWithPhone;
import static com.jey.core.member.MemberFixtures.uniqueName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
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
class MemberApiEdgeTest {

	private static final String STUDENTS = "/api/v1/students";

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
	void 학부모가_네_명인_학생에게_한_명을_더_붙이면_409다() throws Exception {
		String created = send(post(STUDENTS), createBody(uniqueName(), guardians(4))).andExpect(status().isCreated())
				.andReturn()
				.getResponse()
				.getContentAsString();
		long id = ((Number) com.jayway.jsonpath.JsonPath.read(created, "$.id")).longValue();

		send(post(STUDENTS + "/" + id + "/guardians"), guardianBody(randomPhone())).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("MEMBER_TOO_MANY_GUARDIANS"));
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

	// 화면은 대표 연락처 칸만 비워서 그 학생을 보여 준다. 칸을 빼지 않고 null로 내보내 응답의 모양이 같게 한다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 연락처가_없는_학생은_목록과_상세에_대표_연락처가_null로_나온다() throws Exception {
		String prefix = uniqueName();
		studentService.register(registration(prefix + "-가", mother(randomPhone())));
		long withoutContact = studentService.register(registration(prefix + "-나", mother(randomPhone()))).id();
		removeAllContacts(jdbc, withoutContact);

		mockMvc.perform(get(STUDENTS).param("name", prefix))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[0].primaryContact.phone").exists())
				.andExpect(jsonPath("$.items[1].id").value(withoutContact))
				.andExpect(jsonPath("$.items[1].primaryContact").value(nullValue()));
		mockMvc.perform(get(STUDENTS + "/" + withoutContact))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.primaryContact").value(nullValue()))
				.andExpect(jsonPath("$.guardians.length()").value(0));
	}

	// 날짜 입력칸에서 연도를 치다 만 값(0026-10-12)이 그대로 저장되면 목록과 통계가 틀어진다. 화면만 믿지 않고 서버도 범위를 본다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 등록일은_2000년부터_2099년까지만_받는다() throws Exception {
		for (String outOfRange : new String[] { "0026-10-12", "1999-12-31", "2100-01-01" }) {
			send(post(STUDENTS), createBodyRegisteredAt(uniqueName(), outOfRange)).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
					.andExpect(jsonPath("$.errors[?(@.field == 'registeredAt')]").isNotEmpty());
		}
		for (String inRange : new String[] { "2000-01-01", "2099-12-31" }) {
			send(post(STUDENTS), createBodyRegisteredAt(uniqueName(), inRange)).andExpect(status().isCreated())
					.andExpect(jsonPath("$.registeredAt").value(inRange));
		}
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 수정에서도_등록일의_범위를_본다() throws Exception {
		String name = uniqueName();
		long id = studentService.register(registration(name, mother(randomPhone()))).id();

		send(put(STUDENTS + "/" + id), updateBodyRegisteredAt(name, "0026-10-12")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[?(@.field == 'registeredAt')]").isNotEmpty());
		send(put(STUDENTS + "/" + id), updateBodyRegisteredAt(name, "2099-12-31")).andExpect(status().isOk())
				.andExpect(jsonPath("$.registeredAt").value("2099-12-31"));
	}

	// 보내지 않으면 지금처럼 오늘이다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 등록에서_등록일을_보내지_않으면_오늘이다() throws Exception {
		send(post(STUDENTS), createBody(uniqueName(), guardians(1))).andExpect(status().isCreated())
				.andExpect(jsonPath("$.registeredAt").isNotEmpty());
	}

	private static String createBodyRegisteredAt(String name, String registeredAt) {
		return "{\"campusId\": 1, \"name\": \"%s\", \"grade\": \"HIGH_1\", \"registeredAt\": \"%s\", \"guardians\": [%s]}"
			.formatted(name, registeredAt, guardians(1));
	}

	private static String updateBodyRegisteredAt(String name, String registeredAt) {
		return """
				{"campusId": 1, "name": "%s", "grade": "HIGH_1", "status": "ENROLLED", "registeredAt": "%s"}
				""".formatted(name, registeredAt);
	}

	private ResultActions send(MockHttpServletRequestBuilder request, String json) throws Exception {
		return mockMvc.perform(request.with(csrfToken(mockMvc)).with(idempotencyKey()).contentType(MediaType.APPLICATION_JSON).content(json));
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
