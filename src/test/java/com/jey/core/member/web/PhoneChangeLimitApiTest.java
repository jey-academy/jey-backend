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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
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
import static com.jey.core.member.web.StudentSearchRequests.studentSearch;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 가린 번호를 대입으로 알아내려면 번호를 바꿔 가며 넣는다. 틀린 번호는 거부되지 않고 그대로 저장된다(200).
// 거부만 세면 틀린 대입은 한도 없이 할 수 있다. 그래서 전화번호가 실린 변경 요청은 성공해도 센다.
// 운영 값(1시간 100회)은 테스트로 돌리기에 크므로 여기서 작게 정한다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = { "jey.member.phone-change-limit.window=1h", "jey.member.phone-change-limit.max=6" })
class PhoneChangeLimitApiTest {

	private static final int LIMIT = 6;

	private static final long GUESSER = 9401;

	private static final long REGISTRAR = 9402;

	private static final long NAME_EDITOR = 9403;

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
		redis.delete(redis.keys("jey:auth:account-limit:member.phone-*"));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = GUESSER)
	void 번호를_바꿔_가며_넣으면_틀린_번호가_저장돼도_한도에_걸린다() throws Exception {
		// 준비하는 등록도 이 계정의 한 번이다.
		StudentView student = studentService.register(registration(uniqueName(), mother(randomPhone())));
		long guardianId = student.guardians().getFirst().id();

		for (int i = 0; i < LIMIT - 1; i++) {
			changeGuardianPhone(guardianId, randomPhone()).andExpect(status().isOk());
		}

		PhoneNumber next = randomPhone();
		changeGuardianPhone(guardianId, next).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("MEMBER_PHONE_CHANGE_LIMITED"))
				// 한 시간 구간의 남은 시간(초).
				.andExpect(header().string("Retry-After", matchesPattern("[1-9]\\d{0,3}")));
		changeGuardianPhone(guardianId, randomPhone()).andExpect(status().isTooManyRequests());

		// 막힌 요청은 번호를 바꾸지 않았다.
		mockMvc.perform(studentSearch(mockMvc, "phoneLast4", next.last4(), "name", student.name()))
				.andExpect(jsonPath("$.items").isEmpty());
		// 막힌 사실은 구간마다 한 줄이고, 거부의 한도와 다른 이름으로 남는다.
		List<AuditRecord> denied = auditLogService
				.search(GUESSER, null, AuditOutcome.DENIED, null, null, null, null, 0, 20)
				.getContent();
		assertThat(denied).extracting(AuditRecord::getAction).containsExactly("member.phone-change-limited");
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = REGISTRAR)
	void 학생_등록과_학부모_추가도_같은_한도에_든다() throws Exception {
		StudentView student = studentService.register(registration(uniqueName(), mother(randomPhone())));

		for (int i = 0; i < 2; i++) {
			registerStudent(uniqueName(), randomPhone()).andExpect(status().isCreated());
		}
		for (int i = 0; i < 3; i++) {
			addGuardian(student.id(), randomPhone()).andExpect(status().isCreated());
		}

		registerStudent(uniqueName(), randomPhone()).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("MEMBER_PHONE_CHANGE_LIMITED"));
		addGuardian(student.id(), randomPhone()).andExpect(status().isTooManyRequests());
	}

	// 전화번호가 실리지 않은 요청은 대입에 쓸 수 없다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = NAME_EDITOR)
	void 번호가_없는_수정과_조회는_횟수에_들지_않는다() throws Exception {
		StudentView student = studentService.register(registration(uniqueName(), mother(randomPhone())));
		long guardianId = student.guardians().getFirst().id();

		for (int i = 0; i < LIMIT * 2; i++) {
			send(put("/api/v1/guardians/{id}", guardianId), "{\"name\": \"이름-%d\"}".formatted(i))
					.andExpect(status().isOk());
			mockMvc.perform(get("/api/v1/students/{id}", student.id())).andExpect(status().isOk());
		}

		assertThat(redis.opsForHash().get("jey:auth:account-limit:member.phone-change:" + NAME_EDITOR, "count"))
				.isEqualTo("1");
	}

	private ResultActions registerStudent(String name, PhoneNumber motherPhone) throws Exception {
		return send(post("/api/v1/students"), """
				{"campusId": 1, "name": "%s", "grade": "HIGH_1",
				 "guardians": [{"name": "김어머니", "relation": "MOTHER", "phone": "%s"}]}
				""".formatted(name, motherPhone.value()));
	}

	private ResultActions changeGuardianPhone(long guardianId, PhoneNumber phone) throws Exception {
		return send(put("/api/v1/guardians/{id}", guardianId),
				"{\"name\": \"김어머니\", \"phone\": \"%s\"}".formatted(phone.value()));
	}

	private ResultActions addGuardian(long studentId, PhoneNumber phone) throws Exception {
		return send(post("/api/v1/students/{id}/guardians", studentId),
				"{\"name\": \"김아버지\", \"relation\": \"FATHER\", \"phone\": \"%s\"}".formatted(phone.value()));
	}

	private ResultActions send(MockHttpServletRequestBuilder request, String json) throws Exception {
		return mockMvc.perform(request.with(csrfToken(mockMvc)).with(idempotencyKey()).contentType(MediaType.APPLICATION_JSON).content(json));
	}

}
