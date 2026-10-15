package com.jey.core.web;

import com.jey.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import tools.jackson.databind.json.JsonMapper;

import static com.jey.TestCsrf.csrfToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

	@Autowired
	JsonMapper appJsonMapper;

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

	// 로그인 중 DB 오류가 났을 때의 모양이다. 원인이 인증 예외라는 이유로 401 "로그인이 필요합니다"나
	// "비밀번호가 틀렸습니다"가 되면 장애가 로그인 실패로 보인다.
	@Test
	void 원인이_인증_예외인_내부_오류도_500이고_원인은_로그에만_남는다(CapturedOutput output) throws Exception {
		mockMvc.perform(get("/api/v1/test-errors/unexpected-auth-cause"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("COMMON_INTERNAL_ERROR"))
				.andExpect(content().string(not(containsString("secret-host"))))
				.andExpect(content().string(not(containsString("AUTH_INVALID_CREDENTIALS"))))
				.andExpect(content().string(not(containsString("COMMON_UNAUTHENTICATED"))));

		assertThat(output).contains("InternalAuthenticationServiceException").contains("secret-host");
	}

	// 인증 예외라는 이유로 401이 되면 장애가 "로그인이 풀렸다"로 보이고 원인도 남지 않는다.
	@Test
	void 인증을_확인하다_난_내부_오류는_401이_아니라_500이고_원인은_로그에_남는다(CapturedOutput output) throws Exception {
		mockMvc.perform(get("/api/v1/test-errors/auth-service-failure"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("COMMON_INTERNAL_ERROR"))
				.andExpect(content().string(not(containsString("secret-host"))));

		assertThat(output).contains("InternalAuthenticationServiceException").contains("secret-host");
	}

	// 오류 경로는 컨테이너가 넘겨준 오류 상태로 답한다. 직접 부르면 오류 상태가 없으므로 없는 주소로 답한다.
	@Test
	void 로그인한_채_오류_경로를_직접_부르면_404다() throws Exception {
		mockMvc.perform(get("/error"))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
				.andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
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
		mockMvc.perform(post("/api/v1/test-errors/validated").with(csrfToken(mockMvc))
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
		mockMvc.perform(post("/api/v1/test-errors/validated").with(csrfToken(mockMvc))
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
		mockMvc.perform(post("/api/v1/test-errors/validated-mixed").with(csrfToken(mockMvc))
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
		mockMvc.perform(post("/api/v1/test-errors/range").with(csrfToken(mockMvc))
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

	// 폼은 필드별 오류를 그 칸 밑에 보여 준다. 어느 칸인지 없이 "요청 형식이 올바르지 않습니다"만 오면 사용자는 무엇을 고칠지 모른다.
	@Test
	void 전화번호_형식이_틀리면_어느_칸인지_알려_주고_보낸_번호는_싣지_않는다(CapturedOutput output) throws Exception {
		// 오류 본문에는 요청마다 다른 요청 ID가 실린다. 그 안에 우연히 같은 숫자가 나오지 않게 ID를 정해서 보낸다.
		String body = mockMvc.perform(post("/api/v1/test-errors/typed").with(csrfToken(mockMvc))
						.header("X-Request-Id", "fixed-request-id")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"phone\":\"02-9876-5432\",\"count\":1}"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors.length()").value(1))
				.andExpect(jsonPath("$.errors[0].field").value("phone"))
				.andExpect(jsonPath("$.errors[0].message").value("휴대전화 번호 형식이 아닙니다."))
				.andReturn()
				.getResponse()
				.getContentAsString();

		assertThat(body).doesNotContain("9876").doesNotContain("PhoneNumber");
		assertThat(output.getAll()).doesNotContain("02-9876-5432");
	}

	@Test
	void 목록_안의_칸은_몇_번째인지까지_알려_준다() throws Exception {
		mockMvc.perform(post("/api/v1/test-errors/typed").with(csrfToken(mockMvc))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"count\":1,\"contacts\":[{\"name\":\"가\",\"phone\":\"010-1234-5678\"},"
								+ "{\"name\":\"나\",\"phone\":\"1234\"}]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("contacts[1].phone"))
				.andExpect(jsonPath("$.errors[0].message").value("휴대전화 번호 형식이 아닙니다."));
	}

	// 전화번호만이 아니다. 고를 수 없는 값, 숫자 칸의 글자, 틀린 날짜도 어느 칸인지 알려 준다.
	// 문구는 고정이다. 변환 실패 문구에는 내부 타입 이름과 입력값이 들어 있다.
	@Test
	void 읽지_못한_값은_칸_이름과_고정_문구로_답한다() throws Exception {
		String[][] cases = { { "{\"color\":\"PURPLE-SECRET\",\"count\":1}", "color" },
				{ "{\"count\":\"many-SECRET\"}", "count" }, { "{\"count\":1,\"date\":\"2026-13-45-SECRET\"}", "date" },
				{ "{\"count\":1,\"phone\":12345}", "phone" } };
		for (String[] each : cases) {
			String body = mockMvc.perform(post("/api/v1/test-errors/typed").with(csrfToken(mockMvc))
							.contentType(MediaType.APPLICATION_JSON)
							.content(each[0]))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
					.andExpect(jsonPath("$.errors.length()").value(1))
					.andExpect(jsonPath("$.errors[0].field").value(each[1]))
					.andReturn()
					.getResponse()
					.getContentAsString();

			assertThat(body).as(each[1]).doesNotContain("SECRET").doesNotContain("Color").doesNotContain("java.");
		}
	}

	// 요청 본문은 명세의 타입 그대로만 읽는다(ADR-0036). 타입이 다른 값과 범위를 넘는 숫자는 그 칸의 오류다.
	// 지금까지는 대부분 다른 뜻으로 읽혔고(문자열 "1" → 1, 번호 → enum), 빈 금액은 서버 오류, 범위를 넘는 숫자는 칸 없는 요청 형식 오류였다.
	@ParameterizedTest(name = "{0}")
	@CsvSource(delimiter = '|', value = { "{\"id\":\"1\"}|id", "{\"id\":1.9}|id", "{\"id\":\"\"}|id",
			"{\"page\":99999999999}|page", "{\"items\":[{\"page\":1},{\"page\":99999999999}]}|items[1].page",
			"{\"name\":1}|name", "{\"name\":true}|name", "{\"flag\":\"true\"}|flag", "{\"flag\":1}|flag",
			"{\"color\":1}|color", "{\"color\":\"0\"}|color", "{\"date\":20261015}|date",
			"{\"date\":[2026,10,15]}|date", "{\"date\":\"\"}|date", "{\"fee\":\"1000\"}|fee",
			"{\"fee\":\"\"}|fee" })
	void 명세의_타입과_다른_본문_값은_그_칸의_오류다(String json, String field) throws Exception {
		String body = mockMvc.perform(post("/api/v1/test-errors/loose").with(csrfToken(mockMvc))
						.contentType(MediaType.APPLICATION_JSON)
						.content(json))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors.length()").value(1))
				.andExpect(jsonPath("$.errors[0].field").value(field))
				.andExpect(jsonPath("$.errors[0].message").value("형식이 올바르지 않습니다."))
				.andReturn()
				.getResponse()
				.getContentAsString();

		// 보낸 값과 내부 타입 이름은 응답에 나가지 않는다.
		assertThat(body).doesNotContain("99999999999").doesNotContain("java.").doesNotContain("Long");
	}

	@Test
	void 명세의_타입_그대로인_본문은_읽는다() throws Exception {
		mockMvc.perform(post("/api/v1/test-errors/loose").with(csrfToken(mockMvc))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"id":7,"page":2,"name":"","flag":true,"color":"RED","date":"2026-10-15","fee":1000,
								 "items":[{"page":1}]}
								"""))
				.andExpect(status().isOk());
	}

	// JSON이 아닌 본문 형식은 받지 않는다. 다른 형식(YAML)의 변환기는 엄격한 설정도, 모르는 필드 거부도 거치지 않아
	// 명세의 타입 그대로만 읽는다는 규칙을 통째로 우회한다(ADR-0036).
	@Test
	void YAML_본문은_받지_않는다() throws Exception {
		mockMvc.perform(post("/api/v1/test-errors/loose").with(csrfToken(mockMvc))
						.contentType("application/yaml")
						.content("id: '1'\ncolor: 1\nflag: 'true'\nunknownField: 3\n"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.code").value("COMMON_BAD_REQUEST"));
	}

	// 본문 형식을 막아도 API 명세의 YAML 문서는 그대로 나온다(문서는 변환기가 아니라 명세 도구가 직접 쓴다).
	@Test
	void API_명세의_YAML_문서는_그대로_나온다() throws Exception {
		mockMvc.perform(get("/v3/api-docs.yaml"))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("openapi:")));
	}

	// 엄격하게 읽는 것은 요청 본문뿐이다. 앱 전체의 설정(저장해 둔 이벤트를 되살리는 쪽이 쓴다)은 그대로 느슨하다.
	@Test
	void 앱_전체의_JSON_설정은_느슨한_채다() {
		ErrorTestController.Loose read = appJsonMapper.readValue("{\"id\":\"1\",\"date\":[2026,10,15]}",
				ErrorTestController.Loose.class);

		assertThat(read.id()).isEqualTo(1L);
	}

	// 값이 있어야 하는 칸에 null이 왔다. 어느 칸인지 알려 준다.
	@Test
	void 숫자_칸의_null은_그_칸의_오류다() throws Exception {
		mockMvc.perform(post("/api/v1/test-errors/typed").with(csrfToken(mockMvc))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"count\":null}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("count"));
	}

	// 값을 만드는 코드가 "값이 틀렸다"는 뜻으로 던진 예외는 그 칸의 입력 오류다.
	@Test
	void 값이_틀려서_만들지_못한_칸은_그_칸의_오류다() throws Exception {
		mockMvc.perform(post("/api/v1/test-errors/typed").with(csrfToken(mockMvc))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"count\":1,\"ranged\":{\"min\":9,\"max\":1}}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("ranged"))
				.andExpect(jsonPath("$.errors[0].message").value("형식이 올바르지 않습니다."));
	}

	// 값을 만드는 코드의 버그(널 참조, 상태 오류)를 입력 오류로 답하면, 사용자는 멀쩡한 값을 넣고 "형식이 틀렸다"를 보고
	// 서버 로그에는 아무것도 남지 않는다. 서버 오류로 답하고 원인을 로그에 남긴다.
	@Test
	void 값을_만드는_코드의_버그는_입력_오류가_아니라_서버_오류이고_로그에_남는다(CapturedOutput output) throws Exception {
		for (String body : new String[] { "{\"count\":1,\"buggy\":\"anything\"}", "{\"count\":1,\"strict\":{}}" }) {
			String response = mockMvc.perform(post("/api/v1/test-errors/typed").with(csrfToken(mockMvc))
							.contentType(MediaType.APPLICATION_JSON)
							.content(body))
					.andExpect(status().isInternalServerError())
					.andExpect(jsonPath("$.code").value("COMMON_INTERNAL_ERROR"))
					.andExpect(jsonPath("$.errors").doesNotExist())
					.andReturn()
					.getResponse()
					.getContentAsString();

			assertThat(response).doesNotContain("BUGGY-DETAIL").doesNotContain("Buggy").doesNotContain("Strict");
		}
		assertThat(output.getAll()).contains("BUGGY-DETAIL").contains("NullPointerException");
	}

	// 칸 이름은 응답에 실린다. Map의 키는 서버가 정한 이름이 아니라 사용자가 보낸 값이므로 싣지 않는다.
	@Test
	void 사용자가_보낸_키는_칸_이름에_싣지_않는다() throws Exception {
		String body = mockMvc.perform(post("/api/v1/test-errors/typed").with(csrfToken(mockMvc))
						.header("X-Request-Id", "fixed-request-id")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"count\":1,\"scores\":{\"010-1234-5678\":\"abc\"}}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[0].field").value("scores"))
				.andReturn()
				.getResponse()
				.getContentAsString();

		assertThat(body).doesNotContain("1234").doesNotContain("5678");
	}

	// 모르는 칸은 지금 무시한다. 무시하지 않게 바꾸면 사용자가 보낸 칸 이름이 응답에 실릴 수 있다. 그때 이 테스트가 알린다.
	@Test
	void 모르는_칸은_무시한다() throws Exception {
		mockMvc.perform(post("/api/v1/test-errors/typed").with(csrfToken(mockMvc))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"count\":1,\"unknown-SECRET\":\"value\"}"))
				.andExpect(status().is2xxSuccessful());
	}

	// 어느 칸인지 알 수 없는 실패는 지금처럼 요청 형식 오류다.
	@Test
	void 본문의_모양이_틀리면_요청_형식_오류다() throws Exception {
		for (String shape : new String[] { "[1, 2]", "\"text\"", "" }) {
			mockMvc.perform(post("/api/v1/test-errors/typed").with(csrfToken(mockMvc))
							.contentType(MediaType.APPLICATION_JSON)
							.content(shape))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("COMMON_BAD_REQUEST"))
					.andExpect(jsonPath("$.errors").doesNotExist());
		}
	}

	@Test
	void 깨진_JSON_본문은_400() throws Exception {
		mockMvc.perform(post("/api/v1/test-errors/validated").with(csrfToken(mockMvc))
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
		mockMvc.perform(delete("/api/v1/test-errors/business").with(csrfToken(mockMvc)))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(jsonPath("$.code").value("COMMON_METHOD_NOT_ALLOWED"));
	}

	// 남은 시간은 초로 올려서 싣는다. 내려서 실으면 그 시각에 다시 보낸 요청이 또 막힌다.
	@Test
	void 횟수_제한에_막힌_요청은_429와_남은_시간으로_응답한다() throws Exception {
		mockMvc.perform(get("/api/v1/test-errors/rate-limited"))
				.andExpect(status().isTooManyRequests())
				.andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
				.andExpect(header().string("Retry-After", "62"))
				.andExpect(jsonPath("$.status").value(429))
				.andExpect(jsonPath("$.detail").value("너무 많이 요청했습니다."))
				.andExpect(jsonPath("$.code").value("TEST_TOO_MANY"));
	}

	@Test
	void 남은_시간이_1초_미만이어도_1초로_알린다() throws Exception {
		mockMvc.perform(get("/api/v1/test-errors/rate-limited-briefly"))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string("Retry-After", "1"));
	}

	@Test
	void 횟수_제한이_아닌_오류에는_남은_시간이_없다() throws Exception {
		mockMvc.perform(get("/api/v1/test-errors/business"))
				.andExpect(status().isConflict())
				.andExpect(header().doesNotExist("Retry-After"));
	}

}
