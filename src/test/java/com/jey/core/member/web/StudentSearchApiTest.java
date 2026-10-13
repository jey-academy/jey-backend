package com.jey.core.member.web;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static com.jey.TestCsrf.csrfToken;
import static com.jey.core.member.MemberFixtures.DONGTAN;
import static com.jey.core.member.MemberFixtures.mother;
import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.core.member.MemberFixtures.registration;
import static com.jey.core.member.MemberFixtures.uniqueName;
import static com.jey.core.member.web.StudentSearchRequests.SEARCH;
import static com.jey.core.member.web.StudentSearchRequests.studentSearch;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 학생 검색은 조건을 본문으로 받는 POST다 (ADR-0032). 이름과 전화번호 뒷자리가 주소에 실리면 브라우저의 방문 기록과
// 앞단의 접근 로그에 남는다. 조건마다 무엇이 찾아지는지는 MemberApiTest와 MemberApiEdgeTest가 본다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class StudentSearchApiTest {

	private static final String STUDENTS = "/api/v1/students";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	StudentService studentService;

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 조건이_없으면_빈_객체를_보내고_첫_페이지_스무_명을_받는다() throws Exception {
		studentService.register(registration(uniqueName(), mother(randomPhone())));

		mockMvc.perform(studentSearch(mockMvc))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isNotEmpty())
			.andExpect(jsonPath("$.page").value(0))
			.andExpect(jsonPath("$.size").value(20));
	}

	// 값이 없는 조건은 null로 보내도 되고 빼도 된다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 조건을_null로_보내면_그_조건을_걸지_않는다() throws Exception {
		String name = uniqueName();
		studentService.register(registration(name, mother(randomPhone())));

		mockMvc
			.perform(studentSearch(mockMvc, "campusId", null, "name", name, "phoneLast4", null, "page", null, "size",
					null))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.page").value(0))
			.andExpect(jsonPath("$.size").value(20));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 페이지와_크기를_본문으로_받는다() throws Exception {
		String prefix = uniqueName();
		for (int i = 0; i < 3; i++) {
			studentService.register(registration(prefix + i, mother(randomPhone())));
		}

		mockMvc.perform(studentSearch(mockMvc, "name", prefix, "page", 1, "size", 2))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].name").value(prefix + 2))
			.andExpect(jsonPath("$.page").value(1))
			.andExpect(jsonPath("$.size").value(2))
			.andExpect(jsonPath("$.totalElements").value(3));
	}

	// 목록을 받는 길은 하나다. 주소로 조건을 받던 GET은 없다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 주소로_조건을_받던_GET_목록은_없고_상세는_그대로다() throws Exception {
		StudentView student = studentService.register(registration(uniqueName(), mother(randomPhone())));

		mockMvc.perform(get(STUDENTS)).andExpect(status().isMethodNotAllowed());
		mockMvc.perform(get(STUDENTS).param("name", "김")).andExpect(status().isMethodNotAllowed());
		mockMvc.perform(get(STUDENTS + "/{id}", student.id())).andExpect(status().isOk());
	}

	// "search"가 학생 ID 자리로 읽힌다. 서버 오류가 아니라 틀린 요청으로 답해야 한다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 검색_경로를_GET으로_부르면_서버_오류가_아니라_400이다() throws Exception {
		mockMvc.perform(get(SEARCH).param("name", "김")).andExpect(status().isBadRequest());
	}

	// 조건이 주소에 실리면 안 된다. 쿼리 문자열로 보낸 조건은 읽지 않는다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 쿼리_문자열로_보낸_조건은_읽지_않는다() throws Exception {
		String name = uniqueName();
		studentService.register(registration(name, mother(randomPhone())));

		mockMvc.perform(studentSearch(mockMvc, "name", name).param("name", "없는이름" + name).param("size", "0"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1));
	}

	// 바꾸는 자료가 없다. 키 없이 되고, 같은 키로 다른 조건을 보내도 "키를 다시 썼다"로 거부되지 않는다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 멱등_키를_받지_않는다() throws Exception {
		String first = uniqueName();
		String second = uniqueName();
		studentService.register(registration(first, mother(randomPhone())));
		studentService.register(registration(second, mother(randomPhone())));

		mockMvc.perform(studentSearch(mockMvc, "name", first).header("Idempotency-Key", "same-key-for-search"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].name").value(first));
		mockMvc.perform(studentSearch(mockMvc, "name", second).header("Idempotency-Key", "same-key-for-search"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].name").value(second));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void CSRF_토큰이_없으면_403이다() throws Exception {
		mockMvc.perform(post(SEARCH).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isForbidden());
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 본문이_없거나_JSON이_아니면_400이다() throws Exception {
		mockMvc.perform(post(SEARCH).with(csrfToken(mockMvc)).contentType(MediaType.APPLICATION_JSON))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON_BAD_REQUEST"));
		mockMvc.perform(post(SEARCH).with(csrfToken(mockMvc)).contentType(MediaType.APPLICATION_JSON).content("[]"))
			.andExpect(status().isBadRequest());
	}

	// 검색어는 응답에도 로그에도 되돌아 나오지 않는다. 틀린 값을 보냈을 때도 같다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 조건이_틀렸을_때_응답과_로그에_보낸_값이_없다(CapturedOutput output) throws Exception {
		String longName = "검색어노출확인" + "가".repeat(50);

		String wrongLast4 = mockMvc.perform(studentSearch(mockMvc, "phoneLast4", "98765"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[?(@.field == 'phoneLast4')]").isNotEmpty())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String tooLong = mockMvc.perform(studentSearch(mockMvc, "name", longName))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors[?(@.field == 'name')]").isNotEmpty())
			.andReturn()
			.getResponse()
			.getContentAsString();
		// 숫자 자리에 글자: 본문의 값을 읽지 못한 것이다. 어느 칸인지만 알려 준다.
		String notNumber = mockMvc
			.perform(post(SEARCH).with(csrfToken(mockMvc))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"검색어노출확인\", \"page\": \"둘째\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[?(@.field == 'page')]").isNotEmpty())
			.andReturn()
			.getResponse()
			.getContentAsString();

		// 오류 본문의 요청 ID에 우연히 같은 숫자가 나올 수 있어 빼고 본다.
		String responses = (wrongLast4 + tooLong + notNumber).replaceAll("\"requestId\":\"[^\"]*\"", "");
		assertThat(responses).doesNotContain("98765").doesNotContain("검색어노출확인").doesNotContain("둘째");
		assertThat(output.getAll()).doesNotContain("98765").doesNotContain("검색어노출확인").doesNotContain("둘째");
	}

	// 맞는 조건으로 찾았을 때도 로그에 검색어가 남지 않는다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 찾은_요청의_로그에_검색어가_없다(CapturedOutput output) throws Exception {
		String name = uniqueName();
		PhoneNumber phone = randomPhone();
		studentService.register(registration(name, mother(phone)));
		int before = output.getAll().length();

		mockMvc.perform(studentSearch(mockMvc, "name", name, "phoneLast4", phone.last4()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1));

		assertThat(output.getAll().substring(before)).doesNotContain(name);
	}

	// 요청 타입을 로그에 그대로 찍는 실수를 해도 검색어가 남지 않게 한다.
	@Test
	void 요청을_글자로_바꿔도_이름과_뒷자리는_드러나지_않는다() {
		var request = new StudentSearchRequest(1L, "김철수", "5678", 2, 50);

		assertThat(request.toString()).doesNotContain("김철수").doesNotContain("5678").contains("campusId=1")
			.contains("page=2")
			.contains("size=50");
	}

	@Test
	void 페이지와_크기를_주지_않으면_첫_페이지_스무_명이다() {
		var request = new StudentSearchRequest(null, null, null, null, null);

		assertThat(request.page()).isZero();
		assertThat(request.size()).isEqualTo(20);
	}

}
