package com.jey.core.web;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.audit.AuditLogService;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.campus.ClassService;
import com.jey.core.campus.DiscountTypeService;
import com.jey.core.member.StudentService;
import com.jey.core.member.StudentView;
import com.jey.core.shared.PhoneNumber;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static com.jey.TestCsrf.csrfToken;
import static com.jey.TestIdempotency.idempotencyKey;
import static com.jey.core.member.MemberFixtures.mother;
import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.core.member.MemberFixtures.registration;
import static com.jey.core.member.MemberFixtures.uniqueName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 필터 자체는 IdempotencyApiTest가 본다. 여기서는 실제 경로에 붙어 있는지, 다시 보낸 요청이 자료를 하나 더 만들지 않는지를 본다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IdempotentEndpointsApiTest {

	private static final long ADMIN = 9601;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	StudentService studentService;

	@Autowired
	ClassService classService;

	@Autowired
	DiscountTypeService discountTypeService;

	@Autowired
	AuditLogService auditLogService;

	// 등록 응답을 받지 못한 화면이 같은 요청을 다시 보낸 경우다. 전에는 "같은 이름의 학생" 409를 받았다.
	// 화면은 등록됐는지 알 수 없었고, 그 409는 번호가 드러나는 거부로 세어졌다.
	@Test
	@WithJeyUser(role = UserRole.ADMIN, id = ADMIN)
	void 학생_등록을_같은_키로_다시_보내면_학생은_하나이고_처음의_응답을_받는다() throws Exception {
		String name = uniqueName();
		String key = UUID.randomUUID().toString();
		String body = """
				{"campusId": 1, "name": "%s", "grade": "HIGH_1",
				 "guardians": [{"name": "김어머니", "relation": "MOTHER", "phone": "%s"}]}
				""".formatted(name, randomPhone().value());

		MockHttpServletResponse first = send("/api/v1/students", key, body).andExpect(status().isCreated())
				.andReturn()
				.getResponse();
		MockHttpServletResponse again = send("/api/v1/students", key, body).andExpect(status().isCreated())
				.andExpect(header().string("Idempotency-Replayed", "true"))
				.andReturn()
				.getResponse();

		assertThat(again.getContentAsByteArray()).isEqualTo(first.getContentAsByteArray());
		assertThat(studentService.search(null, name, null, 0, 10).getTotalElements()).isEqualTo(1);
		// 일은 한 번만 했다. 학부모 연결의 기록도 한 줄이고, 거부로 남은 것도 없다.
		assertThat(auditLogService.search(ADMIN, "member.guardian-linked", null, null, null, null, null, 0, 10)
				.getTotalElements()).isEqualTo(1);
		assertThat(auditLogService.search(ADMIN, "member.student-duplicated", null, null, null, null, null, 0, 10))
				.isEmpty();
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN, id = ADMIN + 1)
	void 학부모_추가를_같은_키로_다시_보내면_한_명만_붙는다() throws Exception {
		StudentView student = studentService.register(registration(uniqueName(), mother(randomPhone())));
		PhoneNumber fatherPhone = randomPhone();
		String key = UUID.randomUUID().toString();
		String body = "{\"name\": \"김아버지\", \"relation\": \"FATHER\", \"phone\": \"%s\"}"
				.formatted(fatherPhone.value());
		String path = "/api/v1/students/" + student.id() + "/guardians";

		send(path, key, body).andExpect(status().isCreated());
		// 전에는 두 번째가 "이미 연결된 학부모" 409였다.
		send(path, key, body).andExpect(status().isCreated())
				.andExpect(header().string("Idempotency-Replayed", "true"))
				.andExpect(jsonPath("$.guardians.length()").value(2));

		assertThat(studentService.get(student.id()).guardians()).hasSize(2);
	}

	// 반과 할인 유형은 이름이 같아도 막는 규칙이 없다. 다시 보내면 그대로 둘이 생겼다.
	@Test
	@WithJeyUser(role = UserRole.ADMIN, id = ADMIN + 2)
	void 반_추가를_같은_키로_다시_보내면_반은_하나다() throws Exception {
		String name = "반-" + UUID.randomUUID().toString().substring(0, 8);
		String key = UUID.randomUUID().toString();
		String body = "{\"campusId\": 1, \"name\": \"%s\", \"baseFee8\": 280000, \"bookFee\": 0}".formatted(name);

		send("/api/v1/classes", key, body).andExpect(status().isCreated());
		send("/api/v1/classes", key, body).andExpect(status().isCreated())
				.andExpect(header().string("Idempotency-Replayed", "true"));

		assertThat(classService.allClasses(1L)).filteredOn(each -> each.getName().equals(name)).hasSize(1);
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN, id = ADMIN + 3)
	void 할인_유형_추가를_같은_키로_다시_보내면_처음의_응답을_받는다() throws Exception {
		String code = "T_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
		String key = UUID.randomUUID().toString();
		String body = "{\"code\": \"%s\", \"name\": \"시험 할인\", \"rate\": 0.03}".formatted(code);

		MockHttpServletResponse first = send("/api/v1/discount-types", key, body).andExpect(status().isCreated())
				.andReturn()
				.getResponse();
		MockHttpServletResponse again = send("/api/v1/discount-types", key, body).andExpect(status().isCreated())
				.andExpect(header().string("Idempotency-Replayed", "true"))
				.andReturn()
				.getResponse();

		assertThat(again.getContentAsByteArray()).isEqualTo(first.getContentAsByteArray());
		assertThat(discountTypeService.all()).filteredOn(each -> each.getCode().equals(code)).hasSize(1);
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN, id = ADMIN + 4)
	void 키_없이는_자료를_만들_수_없다() throws Exception {
		String name = uniqueName();

		mockMvc.perform(post("/api/v1/students").with(csrfToken(mockMvc))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"campusId": 1, "name": "%s", "grade": "HIGH_1",
						 "guardians": [{"name": "김어머니", "relation": "MOTHER", "phone": "%s"}]}
						""".formatted(name, randomPhone().value())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_IDEMPOTENCY_KEY_REQUIRED"));
		mockMvc.perform(post("/api/v1/classes").with(csrfToken(mockMvc))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"campusId\": 1, \"name\": \"%s\", \"baseFee8\": 280000, \"bookFee\": 0}".formatted(name)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_IDEMPOTENCY_KEY_REQUIRED"));

		assertThat(studentService.search(null, name, null, 0, 10)).isEmpty();
	}

	// 다시 돌려주는 것은 접근 규칙(역할)보다 앞이다. 처음의 응답은 지금의 역할을 다시 묻지 않고 그 계정에 돌려준다(ADR-0027).
	// 받는 것은 그 계정이 이미 받았던 응답이다. 새 키로는 지금의 역할대로 거부된다.
	@Test
	void 역할이_바뀐_뒤에도_같은_키로는_처음의_응답을_받고_새_키로는_지금의_역할대로다() throws Exception {
		long account = ADMIN + 6;
		SecurityContext asAdmin = login(account, UserRole.ADMIN, null);
		SecurityContext asStaff = login(account, UserRole.STAFF, 1L);
		String name = "반-" + UUID.randomUUID().toString().substring(0, 8);
		String body = "{\"campusId\": 1, \"name\": \"%s\", \"baseFee8\": 280000, \"bookFee\": 0}".formatted(name);
		String adminKey = UUID.randomUUID().toString();
		String staffKey = UUID.randomUUID().toString();

		MockHttpServletResponse created = sendAs(asAdmin, adminKey, body).andExpect(status().isCreated())
				.andReturn()
				.getResponse();
		// 직원으로 내려간 뒤: 같은 키는 처음의 201, 새 키는 403.
		MockHttpServletResponse replayed = sendAs(asStaff, adminKey, body).andExpect(status().isCreated())
				.andExpect(header().string("Idempotency-Replayed", "true"))
				.andReturn()
				.getResponse();
		assertThat(replayed.getContentAsByteArray()).isEqualTo(created.getContentAsByteArray());
		sendAs(asStaff, staffKey, body).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("AUTH_ROLE_NOT_ALLOWED"));
		// 거부도 그 시도의 결과로 보관된다. 관리자가 된 뒤에도 그 키로는 같은 거부를 받는다.
		sendAs(asAdmin, staffKey, body).andExpect(status().isForbidden())
				.andExpect(header().string("Idempotency-Replayed", "true"));

		assertThat(classService.allClasses(1L)).filteredOn(each -> each.getName().equals(name)).hasSize(1);
	}

	// 키를 받지 않는 POST는 키 없이 그대로 된다.
	@Test
	@WithJeyUser(role = UserRole.ADMIN, id = ADMIN + 5)
	void 전체_번호_보기와_로그아웃은_키를_요구하지_않는다() throws Exception {
		StudentView student = studentService.register(registration(uniqueName(), mother(randomPhone())));

		mockMvc.perform(post("/api/v1/students/{id}/guardians/{guardianId}/phone/reveal", student.id(),
				student.guardians().getFirst().id()).with(csrfToken(mockMvc))).andExpect(status().isOk());
		mockMvc.perform(post("/api/v1/auth/logout").with(csrfToken(mockMvc))).andExpect(status().isNoContent());
	}

	private ResultActions sendAs(SecurityContext login, String key, String json) throws Exception {
		return mockMvc.perform(post("/api/v1/classes").with(securityContext(login))
				.with(csrfToken(mockMvc))
				.with(idempotencyKey(key))
				.contentType(MediaType.APPLICATION_JSON)
				.content(json));
	}

	private static SecurityContext login(long accountId, UserRole role, Long campusId) {
		AuthenticatedUser user = new AuthenticatedUser(accountId, "test-role-change", "테스트 사용자", role, campusId, null);
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null,
				List.of(new SimpleGrantedAuthority(role.authority()))));
		return context;
	}

	private ResultActions send(String path, String key, String json) throws Exception {
		return mockMvc.perform(post(path).with(csrfToken(mockMvc))
				.with(idempotencyKey(key))
				.contentType(MediaType.APPLICATION_JSON)
				.content(json));
	}

}
