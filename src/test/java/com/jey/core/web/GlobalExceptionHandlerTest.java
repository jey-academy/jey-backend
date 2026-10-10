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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithMockUser
@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {

	private static final String PROBLEM_JSON = "application/problem+json";

	private static final String INVALID_BODY = "{\"name\":\"\",\"count\":0}";

	@Autowired
	MockMvc mockMvc;

	@Test
	void 비즈니스_예외는_에러코드의_상태와_code로_응답한다() throws Exception {
		mockMvc.perform(get("/api/v1/test-errors/business"))
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
				// type은 지정하지 않으므로 본문에 없다(RFC 9457: 없으면 about:blank로 본다).
				.andExpect(jsonPath("$.type").doesNotExist())
				.andExpect(jsonPath("$.title").value("Conflict"))
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.detail").value("이미 사용한 패스입니다."))
				.andExpect(jsonPath("$.instance").value("/api/v1/test-errors/business"))
				.andExpect(jsonPath("$.code").value("COMMON_CONFLICT"));
	}

	// 상태에서 역으로 구한 공통 코드가 아니라 예외가 가진 코드가 그대로 나가야 한다.
	@Test
	void 모듈_에러코드는_COMMON으로_바뀌지_않고_그대로_응답한다() throws Exception {
		mockMvc.perform(get("/api/v1/test-errors/module-code"))
				.andExpect(status().isGone())
				.andExpect(jsonPath("$.title").value("Gone"))
				.andExpect(jsonPath("$.status").value(410))
				.andExpect(jsonPath("$.detail").value("만료된 패스입니다."))
				.andExpect(jsonPath("$.code").value("TEST_PASS_EXPIRED"));
	}

	@Test
	void 컨트롤러에서_난_권한_거부는_403이고_사유를_노출하지_않는다() throws Exception {
		mockMvc.perform(get("/api/v1/test-errors/denied"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"))
				.andExpect(jsonPath("$.detail").value("권한이 없습니다."))
				.andExpect(content().string(not(containsString("내부 사유"))));
	}

	@Test
	void 처리되지_않은_예외는_500과_일반_문구만_응답한다() throws Exception {
		mockMvc.perform(get("/api/v1/test-errors/unexpected"))
				.andExpect(status().isInternalServerError())
				.andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
				.andExpect(jsonPath("$.code").value("COMMON_INTERNAL_ERROR"))
				.andExpect(jsonPath("$.detail").value("일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."))
				.andExpect(content().string(not(containsString("secret-host"))))
				.andExpect(content().string(not(containsString("IllegalStateException"))));
	}

	@Test
	void 처리되지_않은_예외의_원인은_로그에_남는다(CapturedOutput output) throws Exception {
		mockMvc.perform(get("/api/v1/test-errors/unexpected")).andExpect(status().isInternalServerError());

		assertThat(output).contains("IllegalStateException").contains("secret-host");
	}

	// Spring이 500으로 매핑하는 예외는 catch-all을 거치지 않는다. 그래도 로그에는 남아야 한다.
	@Test
	void 반환값_검증_실패는_서버_오류로_응답하고_로그에_남긴다(CapturedOutput output) throws Exception {
		mockMvc.perform(get("/api/v1/test-errors/return-value"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.status").value(500))
				.andExpect(jsonPath("$.code").value("COMMON_INTERNAL_ERROR"))
				.andExpect(jsonPath("$.errors").doesNotExist());

		assertThat(output).contains("HandlerMethodValidationException");
	}

	@Test
	void 본문_검증_실패는_필드별_오류_목록을_담는다() throws Exception {
		mockMvc.perform(post("/api/v1/test-errors/validated").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content(INVALID_BODY))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
				.andExpect(jsonPath("$.detail").value("입력값이 올바르지 않습니다."))
				.andExpect(jsonPath("$.errors", hasSize(2)))
				.andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("name", "count")));
	}

	@Test
	void 검증_문구는_요청_언어와_무관하게_한국어다() throws Exception {
		mockMvc.perform(post("/api/v1/test-errors/validated").with(csrf())
						.header("Accept-Language", "en")
						.contentType(MediaType.APPLICATION_JSON)
						.content(INVALID_BODY))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[?(@.field=='name')].message", hasItem("공백일 수 없습니다")))
				.andExpect(jsonPath("$.errors[?(@.field=='count')].message", hasItem("1 이상이어야 합니다")));
	}

	@Test
	void 파라미터_검증_실패도_필드별_오류_목록을_담는다() throws Exception {
		mockMvc.perform(get("/api/v1/test-errors/param").param("size", "0"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors", hasSize(1)))
				.andExpect(jsonPath("$.errors[0].field").value("size"))
				.andExpect(jsonPath("$.errors[0].message").value("1 이상이어야 합니다"));
	}

	@Test
	void 파라미터_제약과_본문_검증이_함께_있어도_본문_필드명으로_응답한다() throws Exception {
		mockMvc.perform(post("/api/v1/test-errors/validated-mixed").with(csrf())
						.param("size", "1")
						.contentType(MediaType.APPLICATION_JSON)
						.content(INVALID_BODY))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors", hasSize(2)))
				.andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("name", "count")));
	}

	@Test
	void 바인딩_실패는_입력값과_내부_타입을_노출하지_않는다() throws Exception {
		mockMvc.perform(get("/api/v1/test-errors/query").param("from", "2026-13-01"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors", hasSize(1)))
				.andExpect(jsonPath("$.errors[0].field").value("from"))
				.andExpect(jsonPath("$.errors[0].message").value("형식이 올바르지 않습니다."))
				.andExpect(content().string(not(containsString("java.time"))))
				.andExpect(content().string(not(containsString("2026-13-01"))));
	}

	// 특정 필드에 묶이지 않는 오류는 field가 null이다.
	@Test
	void 객체_수준_검증_실패도_오류_목록에_담긴다() throws Exception {
		mockMvc.perform(post("/api/v1/test-errors/range").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"min\":5,\"max\":1}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors", hasSize(1)))
				.andExpect(jsonPath("$.errors[0].field").value(nullValue()))
				.andExpect(jsonPath("$.errors[0].message").value("최솟값이 최댓값보다 클 수 없습니다."));
	}

	@Test
	void 파라미터_타입이_맞지_않으면_400() throws Exception {
		mockMvc.perform(get("/api/v1/test-errors/param").param("size", "abc"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_BAD_REQUEST"))
				.andExpect(jsonPath("$.detail").value("요청 형식이 올바르지 않습니다."));
	}

	@Test
	void 필수_파라미터가_없으면_400() throws Exception {
		mockMvc.perform(get("/api/v1/test-errors/param"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_BAD_REQUEST"));
	}

	@Test
	void 깨진_JSON_본문은_400() throws Exception {
		mockMvc.perform(post("/api/v1/test-errors/validated").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
				.andExpect(jsonPath("$.code").value("COMMON_BAD_REQUEST"))
				.andExpect(jsonPath("$.detail").value("요청 형식이 올바르지 않습니다."));
	}

	@Test
	void 없는_경로는_404() throws Exception {
		mockMvc.perform(get("/api/v1/test-errors/none"))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
				.andExpect(jsonPath("$.detail").value("대상을 찾을 수 없습니다."))
				.andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
	}

	@Test
	void 지원하지_않는_메서드는_405() throws Exception {
		mockMvc.perform(delete("/api/v1/test-errors/business").with(csrf()))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(jsonPath("$.code").value("COMMON_METHOD_NOT_ALLOWED"));
	}

}
