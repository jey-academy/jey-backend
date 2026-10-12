package com.jey.core.member.web;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.member.StudentService;
import com.jey.core.member.StudentView;
import com.jey.core.shared.PhoneNumber;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.junit.jupiter.api.extension.ExtendWith;

import static com.jey.TestCsrf.csrfToken;
import static com.jey.TestIdempotency.idempotencyKey;
import static com.jey.core.member.MemberFixtures.DONGTAN;
import static com.jey.core.member.MemberFixtures.NEUNGDONG;
import static com.jey.core.member.MemberFixtures.father;
import static com.jey.core.member.MemberFixtures.mother;
import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.core.member.MemberFixtures.registration;
import static com.jey.core.member.MemberFixtures.registrationWithPhone;
import static com.jey.core.member.MemberFixtures.uniqueName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class MemberApiTest {

	private static final String STUDENTS = "/api/v1/students";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	StudentService studentService;

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 학생을_등록하면_전화번호는_가린_표시로만_돌아온다(CapturedOutput output) throws Exception {
		String name = uniqueName();
		PhoneNumber motherPhone = randomPhone();
		PhoneNumber studentPhone = randomPhone();

		String body = send(post(STUDENTS), """
				{"campusId": 1, "name": " %s ", "school": "동탄고", "grade": "HIGH_1", "phone": "%s",
				 "guardians": [{"name": "김어머니", "relation": "MOTHER", "phone": "%s"}]}
				""".formatted(name, dashed(studentPhone), dashed(motherPhone)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.campusId").value(1))
				.andExpect(jsonPath("$.classId").isEmpty())
				.andExpect(jsonPath("$.name").value(name))
				.andExpect(jsonPath("$.school").value("동탄고"))
				.andExpect(jsonPath("$.grade").value("HIGH_1"))
				.andExpect(jsonPath("$.status").value("ENROLLED"))
				.andExpect(jsonPath("$.registeredAt").isString())
				.andExpect(jsonPath("$.phone").value(studentPhone.masked()))
				.andExpect(jsonPath("$.guardians[0].id").isNumber())
				.andExpect(jsonPath("$.guardians[0].name").value("김어머니"))
				.andExpect(jsonPath("$.guardians[0].relation").value("MOTHER"))
				.andExpect(jsonPath("$.guardians[0].phone").value(motherPhone.masked()))
				.andExpect(jsonPath("$.guardians[0].linkedToExisting").value(false))
				.andExpect(jsonPath("$.primaryContact.name").value("김어머니"))
				.andExpect(jsonPath("$.primaryContact.relation").value("MOTHER"))
				.andExpect(jsonPath("$.primaryContact.phone").value(motherPhone.masked()))
				.andExpect(jsonPath("$.siblings").isEmpty())
				.andReturn()
				.getResponse()
				.getContentAsString();

		assertNoPlainPhone(body, motherPhone, studentPhone);
		assertNoPlainPhone(output.getAll(), motherPhone, studentPhone);
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 같은_번호의_학부모로_형제를_등록하면_기존_학부모에_붙고_형제로_나온다() throws Exception {
		PhoneNumber phone = randomPhone();
		StudentView first = studentService.register(registration(uniqueName(), mother(phone)));

		send(post(STUDENTS), """
				{"campusId": 1, "name": "%s", "grade": "MIDDLE_2",
				 "guardians": [{"name": "다르게 적은 이름", "relation": "MOTHER", "phone": "%s"}]}
				""".formatted(uniqueName(), phone.value())).andExpect(status().isCreated())
				.andExpect(jsonPath("$.guardians[0].id").value(first.guardians().getFirst().id()))
				.andExpect(jsonPath("$.guardians[0].name").value("김어머니"))
				.andExpect(jsonPath("$.guardians[0].linkedToExisting").value(true))
				.andExpect(jsonPath("$.siblings[0].id").value(first.id()))
				.andExpect(jsonPath("$.siblings[0].name").value(first.name()))
				.andExpect(jsonPath("$.siblings[0].status").value("ENROLLED"));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 학생_상세와_목록을_본다() throws Exception {
		String name = uniqueName();
		PhoneNumber motherPhone = randomPhone();
		PhoneNumber fatherPhone = randomPhone();
		StudentView created = studentService.register(registration(name, father(fatherPhone), mother(motherPhone)));

		String detail = mockMvc.perform(get(STUDENTS + "/{id}", created.id()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value(name))
				.andExpect(jsonPath("$.phone").isEmpty())
				.andExpect(jsonPath("$.guardians.length()").value(2))
				.andExpect(jsonPath("$.guardians[0].relation").value("FATHER"))
				.andExpect(jsonPath("$.guardians[0].linkedToExisting").value(false))
				.andExpect(jsonPath("$.primaryContact.phone").value(motherPhone.masked()))
				.andReturn()
				.getResponse()
				.getContentAsString();
		String list = mockMvc.perform(get(STUDENTS).param("name", name))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].id").value(created.id()))
				.andExpect(jsonPath("$.items[0].name").value(name))
				.andExpect(jsonPath("$.items[0].grade").value("HIGH_1"))
				.andExpect(jsonPath("$.items[0].status").value("ENROLLED"))
				.andExpect(jsonPath("$.items[0].primaryContact.name").value("김어머니"))
				.andExpect(jsonPath("$.items[0].primaryContact.phone").value(motherPhone.masked()))
				.andExpect(jsonPath("$.items[0].guardians").doesNotExist())
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(20))
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.totalPages").value(1))
				.andReturn()
				.getResponse()
				.getContentAsString();

		assertNoPlainPhone(detail + list, motherPhone, fatherPhone);
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 전화번호_뒷자리로_찾는다() throws Exception {
		String name = uniqueName();
		PhoneNumber phone = randomPhone();
		studentService.register(registration(name, mother(phone)));

		mockMvc.perform(get(STUDENTS).param("name", name).param("phoneLast4", phone.last4()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].name").value(name));
		mockMvc.perform(get(STUDENTS).param("name", name).param("phoneLast4", otherLast4(phone)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items").isEmpty())
				.andExpect(jsonPath("$.totalElements").value(0));
	}

	// 학생은 지점으로 묶지 않는다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = NEUNGDONG)
	void 직원은_다른_지점의_학생도_보고_고친다() throws Exception {
		String name = uniqueName();
		StudentView dongtanStudent = studentService.register(registration(name, mother(randomPhone())));

		mockMvc.perform(get(STUDENTS + "/{id}", dongtanStudent.id()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.campusId").value(1));
		mockMvc.perform(get(STUDENTS).param("campusId", "1").param("name", name))
				.andExpect(jsonPath("$.items.length()").value(1));
		send(put(STUDENTS + "/{id}", dongtanStudent.id()), updateBody(name, "HIGH_2", "ENROLLED", null))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.grade").value("HIGH_2"));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 관리자가_학생_정보와_번호를_고치고_번호를_지운다() throws Exception {
		String name = uniqueName();
		PhoneNumber original = randomPhone();
		PhoneNumber changed = randomPhone();
		StudentView created = studentService.register(registrationWithPhone(name, original, mother(randomPhone())));

		// 번호를 보내지 않으면 그대로다.
		send(put(STUDENTS + "/{id}", created.id()), updateBody(name, "HIGH_3", "WITHDRAWN", null))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.grade").value("HIGH_3"))
				.andExpect(jsonPath("$.status").value("WITHDRAWN"))
				.andExpect(jsonPath("$.school").isEmpty())
				.andExpect(jsonPath("$.registeredAt").value("2026-03-02"))
				.andExpect(jsonPath("$.phone").value(original.masked()));
		send(put(STUDENTS + "/{id}", created.id()), updateBody(name, "HIGH_3", "WITHDRAWN", changed))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.phone").value(changed.masked()));

		mockMvc.perform(delete(STUDENTS + "/{id}/phone", created.id()).with(csrfToken(mockMvc)))
				.andExpect(status().isNoContent());
		mockMvc.perform(get(STUDENTS + "/{id}", created.id())).andExpect(jsonPath("$.phone").isEmpty());
	}

	// 화면이 받은 가린 번호를 그대로 다시 보내면 번호가 망가진다. 가린 번호는 전화번호로 읽히지 않아야 한다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 가린_번호를_다시_보내면_400이고_번호는_그대로다() throws Exception {
		String name = uniqueName();
		PhoneNumber original = randomPhone();
		StudentView created = studentService.register(registrationWithPhone(name, original, mother(randomPhone())));

		send(put(STUDENTS + "/{id}", created.id()), """
				{"campusId": 1, "name": "%s", "grade": "HIGH_1", "status": "ENROLLED",
				 "registeredAt": "2026-03-02", "phone": "%s"}
				""".formatted(name, original.masked())).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("phone"))
				.andExpect(jsonPath("$.errors[0].message").value("휴대전화 번호 형식이 아닙니다."));

		mockMvc.perform(get(STUDENTS + "/{id}", created.id()))
				.andExpect(jsonPath("$.phone").value(original.masked()));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 학부모를_추가하고_관계를_고치고_연결을_끊는다() throws Exception {
		StudentView student = studentService.register(registration(uniqueName(), father(randomPhone())));
		long fatherId = student.guardians().getFirst().id();
		PhoneNumber motherPhone = randomPhone();

		send(post(STUDENTS + "/{id}/guardians", student.id()), """
				{"name": "박어머니", "relation": "OTHER", "phone": "%s"}
				""".formatted(dashed(motherPhone))).andExpect(status().isCreated())
				.andExpect(jsonPath("$.guardians.length()").value(2))
				.andExpect(jsonPath("$.guardians[1].name").value("박어머니"))
				.andExpect(jsonPath("$.guardians[1].relation").value("OTHER"))
				.andExpect(jsonPath("$.guardians[1].phone").value(motherPhone.masked()))
				.andExpect(jsonPath("$.guardians[1].linkedToExisting").value(false))
				.andExpect(jsonPath("$.primaryContact.relation").value("FATHER"));
		long motherId = studentService.get(student.id()).guardians().getLast().id();

		send(put(STUDENTS + "/{id}/guardians/{guardianId}", student.id(), motherId), "{\"relation\": \"MOTHER\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.guardians[1].relation").value("MOTHER"))
				.andExpect(jsonPath("$.primaryContact.name").value("박어머니"));

		mockMvc.perform(delete(STUDENTS + "/{id}/guardians/{guardianId}", student.id(), fatherId)
				.with(csrfToken(mockMvc))).andExpect(status().isNoContent());
		mockMvc.perform(get(STUDENTS + "/{id}", student.id()))
				.andExpect(jsonPath("$.guardians.length()").value(1))
				.andExpect(jsonPath("$.guardians[0].id").value(motherId));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 학부모의_이름과_번호를_고친다() throws Exception {
		PhoneNumber original = randomPhone();
		PhoneNumber changed = randomPhone();
		long guardianId = studentService.register(registration(uniqueName(), mother(original)))
				.guardians()
				.getFirst()
				.id();

		send(put("/api/v1/guardians/{id}", guardianId), "{\"name\": \" 박어머니 \"}").andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(guardianId))
				.andExpect(jsonPath("$.name").value("박어머니"))
				.andExpect(jsonPath("$.phone").value(original.masked()));
		String body = send(put("/api/v1/guardians/{id}", guardianId),
				"{\"name\": \"박어머니\", \"phone\": \"%s\"}".formatted(changed.value())).andExpect(status().isOk())
				.andExpect(jsonPath("$.phone").value(changed.masked()))
				.andReturn()
				.getResponse()
				.getContentAsString();

		assertNoPlainPhone(body, changed);
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 업무_오류는_코드로_알려준다() throws Exception {
		PhoneNumber phone = randomPhone();
		PhoneNumber otherPhone = randomPhone();
		String name = uniqueName();
		StudentView student = studentService.register(registration(name, mother(phone)));
		long guardianId = student.guardians().getFirst().id();
		studentService.register(registration(uniqueName(), mother(otherPhone)));

		send(post(STUDENTS), createBody(name, 1, phone)).andExpect(status().isConflict())
				.andExpect(header().string("Content-Type", startsWith("application/problem+json")))
				.andExpect(jsonPath("$.code").value("MEMBER_STUDENT_DUPLICATED"));
		send(post(STUDENTS), createBody(uniqueName(), 999_999, randomPhone())).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MEMBER_INVALID_CAMPUS"));
		send(post(STUDENTS), """
				{"campusId": 1, "classId": 999999999, "name": "%s", "grade": "HIGH_1",
				 "guardians": [{"name": "김어머니", "relation": "MOTHER", "phone": "%s"}]}
				""".formatted(uniqueName(), randomPhone().value())).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MEMBER_INVALID_CLASS"));
		send(post(STUDENTS), "{\"campusId\": 1, \"name\": \"%s\", \"grade\": \"HIGH_1\"}".formatted(uniqueName()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MEMBER_CONTACT_REQUIRED"));
		send(post(STUDENTS), """
				{"campusId": 1, "name": "%s", "grade": "HIGH_1", "guardians": [
				 {"name": "김어머니", "relation": "MOTHER", "phone": "%s"},
				 {"name": "김아버지", "relation": "FATHER", "phone": "%s"}]}
				""".formatted(uniqueName(), phone.value(), dashed(phone))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MEMBER_GUARDIAN_PHONE_REPEATED"));
		mockMvc.perform(get(STUDENTS + "/999999999"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("MEMBER_STUDENT_NOT_FOUND"));
		send(post(STUDENTS + "/{id}/guardians", student.id()),
				"{\"name\": \"김어머니\", \"relation\": \"MOTHER\", \"phone\": \"%s\"}".formatted(phone.value()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("MEMBER_GUARDIAN_ALREADY_LINKED"));
		mockMvc.perform(delete(STUDENTS + "/{id}/guardians/{guardianId}", student.id(), guardianId)
				.with(csrfToken(mockMvc)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("MEMBER_LAST_CONTACT"));
		mockMvc.perform(delete(STUDENTS + "/{id}/guardians/999999999", student.id()).with(csrfToken(mockMvc)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("MEMBER_GUARDIAN_NOT_FOUND"));
		String duplicated = send(put("/api/v1/guardians/{id}", guardianId),
				"{\"name\": \"김어머니\", \"phone\": \"%s\"}".formatted(otherPhone.value()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("MEMBER_GUARDIAN_PHONE_DUPLICATED"))
				.andReturn()
				.getResponse()
				.getContentAsString();

		// 오류 응답에 입력한 번호가 실리지 않는다.
		assertNoPlainPhone(duplicated, otherPhone);
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 값이_틀리면_어느_필드인지_알려준다() throws Exception {
		send(post(STUDENTS), "{\"name\": \"　\", \"school\": \"%s\", \"guardians\": [{\"name\": \"\"}]}"
				.formatted("가".repeat(51))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[?(@.field == 'campusId')]").isNotEmpty())
				.andExpect(jsonPath("$.errors[?(@.field == 'name')]").isNotEmpty())
				.andExpect(jsonPath("$.errors[?(@.field == 'school')]").isNotEmpty())
				.andExpect(jsonPath("$.errors[?(@.field == 'grade')]").isNotEmpty())
				.andExpect(jsonPath("$.errors[?(@.field == 'guardians[0].name')]").isNotEmpty())
				.andExpect(jsonPath("$.errors[?(@.field == 'guardians[0].relation')]").isNotEmpty())
				.andExpect(jsonPath("$.errors[?(@.field == 'guardians[0].phone')]").isNotEmpty());
		send(post(STUDENTS), createBody("가".repeat(51), 1, randomPhone())).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
	}

	// 수정은 통째로 바꾼다. 빠진 값을 "그대로 둔다"로 읽으면 상태를 빼먹은 요청이 조용히 통과한다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 수정은_필수값을_모두_보내야_한다() throws Exception {
		StudentView student = studentService.register(registration(uniqueName(), mother(randomPhone())));

		send(put(STUDENTS + "/{id}", student.id()), "{\"name\": \"%s\"}".formatted(student.name()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[?(@.field == 'campusId')]").isNotEmpty())
				.andExpect(jsonPath("$.errors[?(@.field == 'grade')]").isNotEmpty())
				.andExpect(jsonPath("$.errors[?(@.field == 'status')]").isNotEmpty())
				.andExpect(jsonPath("$.errors[?(@.field == 'registeredAt')]").isNotEmpty());
		send(put(STUDENTS + "/{id}/guardians/{guardianId}", student.id(), student.guardians().getFirst().id()), "{}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field == 'relation')]").isNotEmpty());
		send(put("/api/v1/guardians/{id}", student.guardians().getFirst().id()), "{\"name\": \" \"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field == 'name')]").isNotEmpty());
	}

	// 틀린 입력도 누군가의 전화번호일 가능성이 크다. 응답과 로그에 그대로 남지 않아야 한다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 전화번호나_학년의_형식이_틀리면_400이고_입력값을_되돌려주지_않는다(CapturedOutput output) throws Exception {
		String wrongPhone = "031-8765-4321";

		String body = send(post(STUDENTS), """
				{"campusId": 1, "name": "%s", "grade": "HIGH_1",
				 "guardians": [{"name": "김어머니", "relation": "MOTHER", "phone": "%s"}]}
				""".formatted(uniqueName(), wrongPhone)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("guardians[0].phone"))
				.andReturn()
				.getResponse()
				.getContentAsString();
		String gradeBody = send(post(STUDENTS), """
				{"campusId": 1, "name": "%s", "grade": "고1", "phone": "%s"}
				""".formatted(uniqueName(), randomPhone().value())).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("grade"))
				.andExpect(jsonPath("$.errors[0].message").value("형식이 올바르지 않습니다."))
				.andReturn()
				.getResponse()
				.getContentAsString();

		// 오류 본문의 요청 ID에 우연히 같은 숫자가 나올 수 있어 빼고 본다.
		assertThat(body.replaceAll("\"requestId\":\"[^\"]*\"", "")).doesNotContain("8765").doesNotContain("4321");
		assertThat(gradeBody).doesNotContain("고1").doesNotContain("Grade");
		assertThat(output.getAll()).doesNotContain(wrongPhone).doesNotContain("87654321");
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 목록의_조건이_틀리면_400이다() throws Exception {
		mockMvc.perform(get(STUDENTS).param("phoneLast4", "123"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[?(@.field == 'phoneLast4')]").isNotEmpty());
		mockMvc.perform(get(STUDENTS).param("phoneLast4", "12345")).andExpect(status().isBadRequest());
		mockMvc.perform(get(STUDENTS).param("size", "101"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field == 'size')]").isNotEmpty());
		mockMvc.perform(get(STUDENTS).param("size", "0")).andExpect(status().isBadRequest());
		mockMvc.perform(get(STUDENTS).param("page", "-1"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field == 'page')]").isNotEmpty());
		mockMvc.perform(get(STUDENTS).param("name", "가".repeat(51))).andExpect(status().isBadRequest());
		mockMvc.perform(get(STUDENTS).param("size", "100")).andExpect(status().isOk());
	}

	// 연계 학원 계정은 학원 밖 사람이 쓴다. 학생과 학부모를 볼 수도 고칠 수도 없어야 한다.
	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = 7)
	void 연계_학원은_어느_경로도_쓰지_못한다() throws Exception {
		StudentView student = studentService.register(registration(uniqueName(), mother(randomPhone())));
		long guardianId = student.guardians().getFirst().id();

		forbidden(mockMvc.perform(get(STUDENTS)));
		forbidden(mockMvc.perform(get(STUDENTS).param("phoneLast4", "1234")));
		forbidden(mockMvc.perform(get(STUDENTS + "/{id}", student.id())));
		forbidden(send(post(STUDENTS), createBody(uniqueName(), 1, randomPhone())));
		forbidden(send(put(STUDENTS + "/{id}", student.id()), updateBody(student.name(), "HIGH_1", "ENROLLED", null)));
		forbidden(mockMvc.perform(delete(STUDENTS + "/{id}/phone", student.id()).with(csrfToken(mockMvc))));
		forbidden(send(post(STUDENTS + "/{id}/guardians", student.id()),
				"{\"name\": \"김아버지\", \"relation\": \"FATHER\", \"phone\": \"%s\"}".formatted(randomPhone().value())));
		forbidden(send(put(STUDENTS + "/{id}/guardians/{guardianId}", student.id(), guardianId),
				"{\"relation\": \"FATHER\"}"));
		forbidden(mockMvc.perform(delete(STUDENTS + "/{id}/guardians/{guardianId}", student.id(), guardianId)
				.with(csrfToken(mockMvc))));
		forbidden(send(put("/api/v1/guardians/{id}", guardianId), "{\"name\": \"바뀐 이름\"}"));

		assertThat(studentService.get(student.id()).guardians()).singleElement()
				.satisfies(each -> assertThat(each.name()).isEqualTo("김어머니"));
	}

	@Test
	void 로그인하지_않으면_401이다() throws Exception {
		mockMvc.perform(get(STUDENTS)).andExpect(status().isUnauthorized());
		mockMvc.perform(get(STUDENTS + "/1")).andExpect(status().isUnauthorized());
		send(post(STUDENTS), createBody(uniqueName(), 1, randomPhone())).andExpect(status().isUnauthorized());
		send(put("/api/v1/guardians/1"), "{\"name\": \"이름\"}").andExpect(status().isUnauthorized());
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void CSRF_토큰이_없으면_등록되지_않는다() throws Exception {
		String name = uniqueName();

		mockMvc.perform(post(STUDENTS).contentType(MediaType.APPLICATION_JSON)
				.content(createBody(name, 1, randomPhone()))).andExpect(status().isForbidden());

		mockMvc.perform(get(STUDENTS).param("name", name)).andExpect(jsonPath("$.items").isEmpty());
	}

	private ResultActions send(MockHttpServletRequestBuilder request, String json) throws Exception {
		return mockMvc.perform(request.with(csrfToken(mockMvc)).with(idempotencyKey()).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private static void forbidden(ResultActions result) throws Exception {
		result.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("AUTH_ROLE_NOT_ALLOWED"));
	}

	private static String createBody(String name, long campusId, PhoneNumber guardianPhone) {
		return """
				{"campusId": %d, "name": "%s", "grade": "HIGH_1",
				 "guardians": [{"name": "김어머니", "relation": "MOTHER", "phone": "%s"}]}
				""".formatted(campusId, name, guardianPhone.value());
	}

	private static String updateBody(String name, String grade, String status, PhoneNumber phone) {
		return """
				{"campusId": 1, "name": "%s", "grade": "%s", "status": "%s", "registeredAt": "2026-03-02"%s}
				""".formatted(name, grade, status, (phone != null) ? ", \"phone\": \"" + phone.value() + "\"" : "");
	}

	// 010-1234-5678
	private static String dashed(PhoneNumber phone) {
		String digits = phone.value();
		return digits.substring(0, 3) + "-" + digits.substring(3, 7) + "-" + digits.substring(7);
	}

	private static String otherLast4(PhoneNumber phone) {
		return String.format("%04d", (Integer.parseInt(phone.last4()) + 1) % 10_000);
	}

	// 가린 표시에는 앞 3자리와 뒷자리 4자리만 남는다. 가운데 4자리와 뒷자리가 이어진 8자리가 어디에도 없어야 한다.
	private static void assertNoPlainPhone(String text, PhoneNumber... phones) {
		for (PhoneNumber phone : phones) {
			assertThat(text).doesNotContain(phone.value()).doesNotContain(phone.value().substring(3));
			assertThat(text).doesNotContain(dashed(phone));
		}
	}

}
