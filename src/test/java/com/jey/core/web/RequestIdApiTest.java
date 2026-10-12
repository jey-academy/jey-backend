package com.jey.core.web;

import java.util.List;
import java.util.UUID;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static com.jey.TestCsrf.csrfToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 필터 하나의 동작은 RequestIdFilterTest가, 컨테이너가 다시 보내는 오류 경로는 FilterStageErrorTest가 본다.
// 여기서는 실제 필터 순서 안에서 요청 ID가 응답과 로그에 실리는지 본다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class RequestIdApiTest {

	private static final String HEADER = "X-Request-Id";

	private static final String SENT = "sent-by-proxy-0123456789";

	// 검색어 자리에 실어 보내는 표시. 로그 어디에도 나오면 안 된다.
	private static final String SEARCHED = "query-marker-searched-name";

	@Autowired
	MockMvc mockMvc;

	@Test
	@WithMockUser
	void 성공_응답에는_헤더로만_싣는다() throws Exception {
		mockMvc.perform(get("/api/v1/auth/csrf"))
				.andExpect(status().isNoContent())
				.andExpect(header().string(HEADER, matchesPattern("[0-9a-f]{32}")))
				.andExpect(content().string(""));
	}

	@Test
	@WithMockUser
	void 업무_예외의_본문에_요청_ID를_싣는다() throws Exception {
		expectSameIdInHeaderAndBody(mockMvc.perform(get("/api/v1/test-errors/business"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("COMMON_CONFLICT")));
	}

	@Test
	@WithMockUser
	void 검증_실패의_본문에_요청_ID를_싣는다() throws Exception {
		expectSameIdInHeaderAndBody(mockMvc
				.perform(post("/api/v1/test-errors/validated").with(csrfToken(mockMvc))
						.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\",\"count\":0}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED")));
	}

	// Spring이 본문을 만든 오류다(코드는 나중에 붙는다). 이 갈래에도 실려야 한다.
	@Test
	@WithMockUser
	void 없는_주소의_본문에_요청_ID를_싣는다() throws Exception {
		expectSameIdInHeaderAndBody(mockMvc.perform(get("/api/v1/no-such-path"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND")));
	}

	// 아래 둘은 보안 필터가 막은 요청이다. 컨트롤러까지 가지 않는다.
	@Test
	void 로그인하지_않은_요청의_본문에_요청_ID를_싣는다() throws Exception {
		expectSameIdInHeaderAndBody(mockMvc.perform(get("/api/v1/test-errors/business"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("COMMON_UNAUTHENTICATED")));
	}

	@Test
	@WithMockUser
	void CSRF_거부의_본문에_요청_ID를_싣는다() throws Exception {
		expectSameIdInHeaderAndBody(mockMvc.perform(post("/api/v1/test-errors/validated"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN")));
	}

	// 화면에서 본 ID로 서버 로그를 찾을 수 있어야 한다.
	@Test
	@WithMockUser
	void 서버_오류의_본문과_로그에_같은_요청_ID가_있다(CapturedOutput output) throws Exception {
		String id = expectSameIdInHeaderAndBody(mockMvc.perform(get("/api/v1/test-errors/unexpected"))
				.andExpect(status().isInternalServerError()));

		assertThat(output.getOut().lines().filter(line -> line.contains("서버 오류 응답")))
				.isNotEmpty()
				.allSatisfy(line -> assertThat(line).contains("[" + id + "|"));
	}

	// 앞단(Nginx)이 정한 ID를 그대로 써야 앞단의 로그와 이어 볼 수 있다.
	@Test
	@WithMockUser
	void 보낸_요청_ID가_헤더와_본문에_그대로_온다() throws Exception {
		mockMvc.perform(get("/api/v1/test-errors/business").header(HEADER, SENT))
				.andExpect(status().isConflict())
				.andExpect(header().string(HEADER, SENT))
				.andExpect(jsonPath("$.requestId").value(SENT));
	}

	// 형식에 맞지 않는 값은 로그와 응답에 그대로 싣지 않는다. 요청은 거부하지 않는다.
	@Test
	@WithMockUser
	void 형식에_맞지_않는_요청_ID는_버리고_요청은_그대로_처리한다() throws Exception {
		String id = expectSameIdInHeaderAndBody(mockMvc
				.perform(get("/api/v1/test-errors/business").header(HEADER, "<script>alert(1)</script>"))
				.andExpect(status().isConflict()));

		assertThat(id).matches("[0-9a-f]{32}");
	}

	// 계정 ID만 넣는다. 아이디와 이름은 로그에 남기지 않는다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1, id = 9601)
	void 로그인한_요청의_로그에는_계정_ID가_찍힌다(CapturedOutput output) throws Exception {
		String id = expectSameIdInHeaderAndBody(mockMvc.perform(get("/api/v1/test-errors/unexpected"))
				.andExpect(status().isInternalServerError()));

		assertThat(output.getOut().lines().filter(line -> line.contains("서버 오류 응답")))
				.isNotEmpty()
				.allSatisfy(line -> assertThat(line).contains("[" + id + "|9601] ")
						.doesNotContain("test-staff").doesNotContain("테스트 사용자"));
	}

	// 스레드는 다음 요청이 다시 쓴다. 앞 요청의 계정이 남아 있으면 로그인하지 않은 요청이 그 계정의 것으로 보인다.
	// MockMvc는 요청을 모두 이 테스트의 스레드에서 처리하므로 같은 스레드를 다시 쓰는 경우와 같다.
	@Test
	void 앞_요청의_계정_ID가_다음_요청의_로그에_남지_않는다(CapturedOutput output) throws Exception {
		mockMvc.perform(get("/api/v1/test-errors/unexpected")
						.with(authentication(UsernamePasswordAuthenticationToken.authenticated(
								new AuthenticatedUser(9602L, "test-staff", "테스트 사용자", UserRole.STAFF, 1L, null), null,
								List.of(new SimpleGrantedAuthority(UserRole.STAFF.authority()))))))
				.andExpect(status().isInternalServerError());

		String id = mockMvc.perform(post("/api/v1/test-errors/validated"))
				.andExpect(status().isForbidden())
				.andReturn().getResponse().getHeader(HEADER);

		assertThat(output.getOut().lines().filter(line -> line.contains("CSRF 토큰 거부")))
				.isNotEmpty()
				.allSatisfy(line -> assertThat(line).contains("[" + id + "|] "));
		assertThat(output).contains("|9602] ");
	}

	// 쿼리 문자열에는 검색어(학생 이름, 전화번호 뒷자리)가 실린다. 로그에는 경로만 남긴다.
	// 로그를 남기는 갈래를 하나씩 지난다: 서버 오류, 접근 거부, CSRF 거부, 멱등 키 필터의 거부.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1, id = 9603)
	void 쿼리_문자열은_로그에_남지_않는다(CapturedOutput output) throws Exception {
		mockMvc.perform(get("/api/v1/test-errors/unexpected").queryParam("name", SEARCHED))
				.andExpect(status().isInternalServerError());
		mockMvc.perform(get("/api/v1/audit-logs").queryParam("name", SEARCHED))
				.andExpect(status().isForbidden());
		mockMvc.perform(post("/api/v1/test-errors/validated").queryParam("name", SEARCHED))
				.andExpect(status().isForbidden());
		String key = UUID.randomUUID().toString();
		mockMvc.perform(post("/api/v1/test-idempotency/things").queryParam("note", SEARCHED)
						.with(csrfToken(mockMvc)).header("Idempotency-Key", key)
						.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"first\"}"))
				.andExpect(status().isCreated());
		mockMvc.perform(post("/api/v1/test-idempotency/things").queryParam("note", SEARCHED)
						.with(csrfToken(mockMvc)).header("Idempotency-Key", key)
						.contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"second\"}"))
				.andExpect(status().isUnprocessableEntity());

		// 각 갈래가 실제로 로그를 남겼는지 먼저 본다. 남긴 것이 없으면 아래 확인은 뜻이 없다.
		assertThat(output).contains("서버 오류 응답").contains("접근 거부").contains("CSRF 토큰 거부")
				.contains("이미 다른 내용에 쓴 멱등 키가 왔다");
		assertThat(output).doesNotContain(SEARCHED);
	}

	private static String expectSameIdInHeaderAndBody(ResultActions result) throws Exception {
		String id = result.andReturn().getResponse().getHeader(HEADER);
		assertThat(id).isNotBlank();
		result.andExpect(jsonPath("$.requestId").value(id));
		return id;
	}

}
