package com.jey.core.member.web;

import static com.jey.TestCsrf.csrfToken;
import static com.jey.TestIdempotency.idempotencyKey;
import static com.jey.core.member.MemberFixtures.DONGTAN;
import static com.jey.core.member.MemberFixtures.mother;
import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.core.member.MemberFixtures.registration;
import static com.jey.core.member.MemberFixtures.uniqueName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.member.StudentService;
import com.jey.core.member.StudentView;

// 저장은 됐는데 응답만 놓친 화면이 같은 내용을 다시 보내는 경우(ADR-0027 개정).
// 버전은 그 사이 올라 있으므로, 키가 없으면 자기 저장을 "다른 사람이 먼저 고쳤다"로 거부받는다.
// 같은 키로 다시 보내면 다시 실행하지 않고 처음의 응답을 받는다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UpdateRetryApiTest {

	private static final String STUDENTS = "/api/v1/students";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	StudentService studentService;

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 응답을_놓친_학생_수정을_같은_키로_다시_보내면_처음의_응답을_받는다() throws Exception {
		StudentView student = studentService.register(registration(uniqueName(), mother(randomPhone())));
		String key = UUID.randomUUID().toString();
		String body = updateBody(student.version(), student.name(), "HIGH_2");

		MockHttpServletResponse first = send(put(STUDENTS + "/{id}", student.id()), key, body)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.version").value(student.version() + 1))
				.andReturn()
				.getResponse();
		MockHttpServletResponse again = send(put(STUDENTS + "/{id}", student.id()), key, body)
				.andExpect(status().isOk())
				.andExpect(header().string("Idempotency-Replayed", "true"))
				.andReturn()
				.getResponse();

		assertThat(again.getContentAsByteArray()).isEqualTo(first.getContentAsByteArray());
		// 한 번만 저장됐다.
		assertThat(studentService.get(student.id()).version()).isEqualTo(student.version() + 1);
	}

	// 처음 응답 이후에 다른 사람이 고친 것을 되돌리지 않는다. 다시 실행하지 않고 보관한 응답만 돌려준다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 다시_보내기_전에_다른_사람이_고쳤어도_그_변경은_남는다() throws Exception {
		StudentView student = studentService.register(registration(uniqueName(), mother(randomPhone())));
		String key = UUID.randomUUID().toString();
		String mine = updateBody(student.version(), student.name(), "HIGH_2");

		send(put(STUDENTS + "/{id}", student.id()), key, mine).andExpect(status().isOk());
		send(put(STUDENTS + "/{id}", student.id()), UUID.randomUUID().toString(),
				updateBody(student.version() + 1, student.name(), "HIGH_3"))
				.andExpect(status().isOk());
		send(put(STUDENTS + "/{id}", student.id()), key, mine).andExpect(status().isOk())
				.andExpect(header().string("Idempotency-Replayed", "true"))
				.andExpect(jsonPath("$.grade").value("HIGH_2"));

		mockMvc.perform(get(STUDENTS + "/{id}", student.id()))
				.andExpect(jsonPath("$.grade").value("HIGH_3"))
				.andExpect(jsonPath("$.version").value(student.version() + 2));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 처음이_낡은_버전으로_거부됐으면_같은_키의_재전송도_같은_거부다() throws Exception {
		StudentView student = studentService.register(registration(uniqueName(), mother(randomPhone())));
		send(put(STUDENTS + "/{id}", student.id()), UUID.randomUUID().toString(),
				updateBody(student.version(), student.name(), "HIGH_2"))
				.andExpect(status().isOk());
		String key = UUID.randomUUID().toString();
		String stale = updateBody(student.version(), student.name(), "HIGH_3");

		send(put(STUDENTS + "/{id}", student.id()), key, stale).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("MEMBER_STUDENT_CHANGED"));
		send(put(STUDENTS + "/{id}", student.id()), key, stale).andExpect(status().isConflict())
				.andExpect(header().string("Idempotency-Replayed", "true"))
				.andExpect(jsonPath("$.code").value("MEMBER_STUDENT_CHANGED"));
	}

	// 값을 고쳐 내는 것은 새 시도다. 같은 키로는 처음의 응답을 돌려줄 수 없다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 값을_고쳐_같은_키로_보내면_422이고_고친_값은_저장되지_않는다() throws Exception {
		StudentView student = studentService.register(registration(uniqueName(), mother(randomPhone())));
		String key = UUID.randomUUID().toString();

		send(put(STUDENTS + "/{id}", student.id()), key, updateBody(student.version(), student.name(), "HIGH_2"))
				.andExpect(status().isOk());
		send(put(STUDENTS + "/{id}", student.id()), key, updateBody(student.version(), student.name(), "HIGH_3"))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code").value("COMMON_IDEMPOTENCY_KEY_REUSED"));

		mockMvc.perform(get(STUDENTS + "/{id}", student.id())).andExpect(jsonPath("$.grade").value("HIGH_2"));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 학생_수정과_학부모_수정은_키가_없으면_실행하지_않는다() throws Exception {
		StudentView student = studentService.register(registration(uniqueName(), mother(randomPhone())));
		long guardianId = student.guardians().getFirst().id();

		send(put(STUDENTS + "/{id}", student.id()), null, updateBody(student.version(), student.name(), "HIGH_2"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_IDEMPOTENCY_KEY_REQUIRED"));
		send(put("/api/v1/guardians/{id}", guardianId), null, "{\"name\": \"바뀐 이름\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_IDEMPOTENCY_KEY_REQUIRED"));

		StudentView after = studentService.get(student.id());
		assertThat(after.version()).isEqualTo(student.version());
		assertThat(after.guardians().getFirst().name()).isEqualTo(student.guardians().getFirst().name());
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 응답을_놓친_학부모_수정도_같은_키면_다시_실행하지_않는다() throws Exception {
		StudentView student = studentService.register(registration(uniqueName(), mother(randomPhone())));
		long guardianId = student.guardians().getFirst().id();
		String key = UUID.randomUUID().toString();

		send(put("/api/v1/guardians/{id}", guardianId), key, "{\"name\": \"박어머니\"}").andExpect(status().isOk());
		send(put("/api/v1/guardians/{id}", guardianId), UUID.randomUUID().toString(), "{\"name\": \"최어머니\"}")
				.andExpect(status().isOk());
		send(put("/api/v1/guardians/{id}", guardianId), key, "{\"name\": \"박어머니\"}").andExpect(status().isOk())
				.andExpect(header().string("Idempotency-Replayed", "true"))
				.andExpect(jsonPath("$.name").value("박어머니"));

		assertThat(studentService.get(student.id()).guardians().getFirst().name()).isEqualTo("최어머니");
	}

	private ResultActions send(MockHttpServletRequestBuilder request, String key, String json) throws Exception {
		request.with(csrfToken(mockMvc)).contentType(MediaType.APPLICATION_JSON).content(json);
		if (key != null) {
			request.with(idempotencyKey(key));
		}
		return mockMvc.perform(request);
	}

	private static String updateBody(long version, String name, String grade) {
		return """
				{"version": %d, "campusId": 1, "name": "%s", "grade": "%s", "status": "ENROLLED", "registeredAt": "2026-03-02"}
				""".formatted(version, name, grade);
	}

}
