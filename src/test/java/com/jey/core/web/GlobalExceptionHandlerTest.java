package com.jey.core.web;

import com.jey.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
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
class GlobalExceptionHandlerTest {

	private static final String PROBLEM_JSON = "application/problem+json";

	@Autowired
	MockMvc mockMvc;

	@Test
	void 비즈니스_예외는_에러코드의_상태와_code로_응답한다() throws Exception {
		mockMvc.perform(get("/api/v1/test-errors/business"))
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
				// type은 about:blank라 본문에서 생략된다(RFC 9457: 없으면 about:blank로 본다).
				.andExpect(jsonPath("$.type").doesNotExist())
				.andExpect(jsonPath("$.title").value("Conflict"))
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.detail").value("이미 사용한 패스입니다."))
				.andExpect(jsonPath("$.instance").value("/api/v1/test-errors/business"))
				.andExpect(jsonPath("$.code").value("COMMON_CONFLICT"));
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
	void 본문_검증_실패는_필드별_오류_목록을_담는다() throws Exception {
		mockMvc.perform(post("/api/v1/test-errors/validated").with(csrf())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"\",\"count\":0}"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
				.andExpect(jsonPath("$.detail").value("입력값이 올바르지 않습니다."))
				.andExpect(jsonPath("$.errors", hasSize(2)))
				.andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("name", "count")))
				.andExpect(jsonPath("$.errors[0].message").isNotEmpty());
	}

	@Test
	void 파라미터_검증_실패도_필드별_오류_목록을_담는다() throws Exception {
		mockMvc.perform(get("/api/v1/test-errors/param").param("size", "0"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors", hasSize(1)))
				.andExpect(jsonPath("$.errors[0].field").value("size"));
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
	void 깨진_JSON_본문은_400이고_파서_메시지를_노출하지_않는다() throws Exception {
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
				.andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
	}

	@Test
	void 지원하지_않는_메서드는_405() throws Exception {
		mockMvc.perform(delete("/api/v1/test-errors/business").with(csrf()))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(jsonPath("$.code").value("COMMON_METHOD_NOT_ALLOWED"));
	}

}
