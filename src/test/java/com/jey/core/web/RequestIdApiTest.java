package com.jey.core.web;

import com.jey.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static com.jey.TestCsrf.csrfToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
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

	private static String expectSameIdInHeaderAndBody(ResultActions result) throws Exception {
		String id = result.andReturn().getResponse().getHeader(HEADER);
		assertThat(id).isNotBlank();
		result.andExpect(jsonPath("$.requestId").value(id));
		return id;
	}

}
