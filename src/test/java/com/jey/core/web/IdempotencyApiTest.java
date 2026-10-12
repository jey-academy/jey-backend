package com.jey.core.web;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.auth.api.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static com.jey.TestCsrf.csrfToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 응답을 받지 못한 화면은 같은 요청을 다시 보낸다. 같은 키로 온 같은 요청은 한 번만 실행되고, 다시 보낸 쪽은 처음의 응답을 받아야 한다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IdempotencyApiTest {

	private static final String BASE = "/api/v1/test-idempotency";

	private static final String PROBLEM_JSON = "application/problem+json";

	private static final String THING = "{\"name\": \"김철수\"}";

	private static final long SENDER = 9501;

	private static final long OTHER = 9502;

	@Autowired
	MockMvc mockMvc;

	private String key;

	@BeforeEach
	void setUp() {
		IdempotencyTestController.EXECUTIONS.set(0);
		IdempotencyTestController.entered = new CountDownLatch(0);
		IdempotencyTestController.proceed = new CountDownLatch(0);
		key = UUID.randomUUID().toString();
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1, id = SENDER)
	void 같은_키로_다시_보내면_한_번만_실행하고_처음의_응답을_돌려준다() throws Exception {
		MockHttpServletResponse first = send("/things", key, THING).andExpect(status().isCreated())
				.andExpect(jsonPath("$.serial").value(1))
				.andExpect(jsonPath("$.name").value("김철수"))
				.andExpect(header().doesNotExist("Idempotency-Replayed"))
				.andReturn()
				.getResponse();

		for (int i = 0; i < 3; i++) {
			MockHttpServletResponse again = send("/things", key, THING).andExpect(status().isCreated())
					.andExpect(header().string("Idempotency-Replayed", "true"))
					.andReturn()
					.getResponse();
			// 바이트까지 같다. 다시 만든 응답이 아니라 보관한 응답이다.
			assertThat(again.getContentAsByteArray()).isEqualTo(first.getContentAsByteArray());
			assertThat(again.getContentType()).isEqualTo(first.getContentType());
		}

		assertThat(IdempotencyTestController.EXECUTIONS).hasValue(1);
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1, id = SENDER)
	void 키가_다르면_따로_실행한다() throws Exception {
		send("/things", key, THING).andExpect(jsonPath("$.serial").value(1));
		send("/things", UUID.randomUUID().toString(), THING).andExpect(jsonPath("$.serial").value(2));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1, id = SENDER)
	void 본문이_없는_응답도_그대로_돌려준다() throws Exception {
		send("/empty", key, null).andExpect(status().isNoContent());
		send("/empty", key, null).andExpect(status().isNoContent())
				.andExpect(header().string("Idempotency-Replayed", "true"))
				.andExpect(content().string(""));

		assertThat(IdempotencyTestController.EXECUTIONS).hasValue(1);
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1, id = SENDER)
	void 키가_없거나_형식이_틀리면_실행하지_않는다() throws Exception {
		expectSecurityHeaders(send("/things", null, THING).andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
				.andExpect(jsonPath("$.code").value("COMMON_IDEMPOTENCY_KEY_REQUIRED"))
				.andExpect(jsonPath("$.instance").value(BASE + "/things")));
		for (String malformed : List.of("", "short", "공백과 한글", "a".repeat(129), "has space in it")) {
			send("/things", malformed, THING).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("COMMON_IDEMPOTENCY_KEY_REQUIRED"));
		}

		assertThat(IdempotencyTestController.EXECUTIONS).hasValue(0);
	}

	// 같은 키로 다른 일을 시키면 앞의 응답을 돌려줄 수 없다. 하지 않은 일을 했다고 답하게 된다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1, id = SENDER)
	void 같은_키에_본문이나_주소가_다르면_거부한다() throws Exception {
		send("/things", key, THING).andExpect(status().isCreated());

		expectSecurityHeaders(send("/things", key, "{\"name\": \"이영희\"}").andExpect(status().isUnprocessableEntity())
				.andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
				.andExpect(jsonPath("$.code").value("COMMON_IDEMPOTENCY_KEY_REUSED")));
		mockMvc.perform(request("/things", key, THING).queryParam("note", "other"))
				.andExpect(status().isUnprocessableEntity());
		send("/empty", key, THING).andExpect(status().isUnprocessableEntity());

		assertThat(IdempotencyTestController.EXECUTIONS).hasValue(1);
	}

	// 거부도 그 시도의 결과다. 같은 키로 다시 보내면 다시 실행하지 않고 같은 거부를 돌려준다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1, id = SENDER)
	void 거부된_요청의_응답도_보관한다() throws Exception {
		for (int i = 0; i < 2; i++) {
			send("/rejected", key, null).andExpect(status().isConflict())
					.andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
					.andExpect(jsonPath("$.code").value("COMMON_CONFLICT"))
					.andExpect(jsonPath("$.detail").value("이미 사용한 패스입니다."));
		}
		assertThat(IdempotencyTestController.EXECUTIONS).hasValue(1);

		String invalidKey = UUID.randomUUID().toString();
		for (int i = 0; i < 2; i++) {
			send("/things", invalidKey, "{\"name\": \"\"}").andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
		}
		assertThat(IdempotencyTestController.EXECUTIONS).hasValue(1);
	}

	// 서버 오류는 일이 됐는지 안 됐는지 모르는 답이다. 보관하면 보관 기간 내내 같은 오류가 나간다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1, id = SENDER)
	void 서버_오류는_보관하지_않아서_같은_키로_다시_시도할_수_있다() throws Exception {
		send("/broken", key, null).andExpect(status().isInternalServerError());
		send("/broken", key, null).andExpect(status().isInternalServerError())
				.andExpect(header().doesNotExist("Idempotency-Replayed"));

		assertThat(IdempotencyTestController.EXECUTIONS).hasValue(2);
	}

	// "잠시 후 다시"를 보관하면 그 말대로 다시 보낸 요청이 같은 답을 받는다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1, id = SENDER)
	void 횟수_한도에_막힌_응답은_보관하지_않는다() throws Exception {
		send("/limited", key, null).andExpect(status().isTooManyRequests());
		send("/limited", key, null).andExpect(status().isTooManyRequests())
				.andExpect(header().string("Retry-After", "300"))
				.andExpect(header().doesNotExist("Idempotency-Replayed"));

		assertThat(IdempotencyTestController.EXECUTIONS).hasValue(2);
	}

	// 처음 요청이 끝나지 않았는데 화면이 다시 보낸 경우다. 기다리게 하면 연결만 쌓인다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1, id = SENDER)
	void 처리_중인_키로_다시_보내면_거부하고_끝난_뒤에는_그_응답을_돌려준다() throws Exception {
		IdempotencyTestController.entered = new CountDownLatch(1);
		IdempotencyTestController.proceed = new CountDownLatch(1);
		RequestPostProcessor login = securityContext(SecurityContextHolder.getContext());
		RequestPostProcessor csrf = csrfToken(mockMvc);
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<MockHttpServletResponse> slow = pool.submit(() -> mockMvc
					.perform(post(BASE + "/slow").with(login).with(csrf).header("Idempotency-Key", key))
					.andReturn()
					.getResponse());
			assertThat(IdempotencyTestController.entered.await(10, TimeUnit.SECONDS)).isTrue();

			expectSecurityHeaders(send("/slow", key, null).andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("COMMON_IDEMPOTENCY_IN_PROGRESS")));

			IdempotencyTestController.proceed.countDown();
			assertThat(slow.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
			send("/slow", key, null).andExpect(status().isOk())
					.andExpect(header().string("Idempotency-Replayed", "true"))
					.andExpect(jsonPath("$.serial").value(1));
			assertThat(IdempotencyTestController.EXECUTIONS).hasValue(1);
		}
		finally {
			IdempotencyTestController.proceed.countDown();
			pool.shutdownNow();
		}
	}

	// 버튼을 연달아 누르거나 화면이 한꺼번에 다시 보낸 경우다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1, id = SENDER)
	void 같은_키를_한꺼번에_보내도_한_번만_실행한다() throws Exception {
		int threads = 24;
		SecurityContext login = SecurityContextHolder.getContext();
		RequestPostProcessor csrf = csrfToken(mockMvc);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				results.add(pool.submit(() -> {
					ready.countDown();
					start.await();
					return mockMvc
							.perform(post(BASE + "/things").with(securityContext(login))
									.with(csrf)
									.header("Idempotency-Key", key)
									.contentType(MediaType.APPLICATION_JSON)
									.content(THING))
							.andReturn()
							.getResponse()
							.getStatus();
				}));
			}
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> result : results) {
				statuses.add(result.get(15, TimeUnit.SECONDS));
			}

			// 실행한 하나와, 그 응답을 돌려받은 것(201), 처리 중이라 거부된 것(409)뿐이다.
			assertThat(statuses).allMatch(status -> status == 201 || status == 409);
			assertThat(statuses).contains(201);
			assertThat(IdempotencyTestController.EXECUTIONS).hasValue(1);
		}
		finally {
			pool.shutdownNow();
		}
	}

	// 키는 화면이 만든다. 두 계정이 같은 키를 쓰면 남의 응답을 받게 되어서는 안 된다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1, id = OTHER)
	void 다른_계정이_쓴_키는_이_계정의_요청과_무관하다() throws Exception {
		SecurityContext other = SecurityContextHolder.getContext();
		send("/things", key, THING).andExpect(jsonPath("$.serial").value(1));

		SecurityContext sender = new WithJeyUser.Factory().createSecurityContext(staff(SENDER));
		mockMvc.perform(request("/things", key, THING).with(securityContext(sender)))
				.andExpect(status().isCreated())
				.andExpect(header().doesNotExist("Idempotency-Replayed"))
				.andExpect(jsonPath("$.serial").value(2));
		mockMvc.perform(request("/things", key, THING).with(securityContext(other)))
				.andExpect(header().string("Idempotency-Replayed", "true"))
				.andExpect(jsonPath("$.serial").value(1));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1, id = SENDER)
	void 키를_받지_않는_경로는_헤더가_있어도_매번_실행한다() throws Exception {
		send("/unguarded", key, null).andExpect(jsonPath("$.serial").value(1));
		send("/unguarded", key, null).andExpect(jsonPath("$.serial").value(2))
				.andExpect(header().doesNotExist("Idempotency-Replayed"));
		send("/unguarded", null, null).andExpect(jsonPath("$.serial").value(3));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1, id = SENDER)
	void 본문이_너무_크면_실행하지_않는다() throws Exception {
		String huge = "{\"name\": \"" + "가".repeat(400_000) + "\"}";

		expectSecurityHeaders(send("/things", key, huge).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_BAD_REQUEST")));

		assertThat(IdempotencyTestController.EXECUTIONS).hasValue(0);
	}

	// 필터가 본문을 먼저 읽으면 폼의 값이 컨트롤러에 닿지 않는다. 빈 값으로 실행하지 않고 거부한다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1, id = SENDER)
	void 폼으로_보낸_요청은_실행하지_않는다() throws Exception {
		mockMvc.perform(post(BASE + "/things").with(csrfToken(mockMvc))
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.content("name=kim"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
		mockMvc.perform(post(BASE + "/empty").with(csrfToken(mockMvc))
				.header("Idempotency-Key", key)
				.contentType("multipart/form-data; boundary=x")
				.content("--x--"))
				.andExpect(status().isUnsupportedMediaType());

		assertThat(IdempotencyTestController.EXECUTIONS).hasValue(0);
		// 키는 쓰이지 않았다. 같은 키로 제대로 다시 보낼 수 있다.
		send("/things", key, THING).andExpect(status().isCreated());
	}

	// 맞는 컨트롤러 메서드가 없는 요청은 키를 요구하지 않고 그대로 넘긴다. 뒤에서 원래의 이유로 거절된다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1, id = SENDER)
	void 맞는_메서드가_없는_요청은_키와_무관하게_원래의_이유로_거절된다() throws Exception {
		mockMvc.perform(post("/api/v1/auth/me").with(csrfToken(mockMvc)))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(jsonPath("$.code").value("COMMON_METHOD_NOT_ALLOWED"));
		mockMvc.perform(post(BASE + "/no-such-thing").with(csrfToken(mockMvc)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
		// 본문의 타입이 맞지 않는 요청. 필터의 폼 검사와는 다른 길이다(JSON만 받는 메서드에 글자를 보냄).
		mockMvc.perform(post(BASE + "/json-only").with(csrfToken(mockMvc))
				.contentType(MediaType.TEXT_PLAIN)
				.content("kim"))
				.andExpect(status().isUnsupportedMediaType());

		assertThat(IdempotencyTestController.EXECUTIONS).hasValue(0);
	}

	// 로그인하지 않은 요청은 보안 필터가 먼저 막는다. 키를 보기 전이다.
	@Test
	void 로그인하지_않으면_키가_있어도_401이다() throws Exception {
		send("/things", key, THING).andExpect(status().isUnauthorized());

		assertThat(IdempotencyTestController.EXECUTIONS).hasValue(0);
	}

	// 다른 출처의 프론트가 오류 응답을 읽고, 다시 돌려준 응답인지 알 수 있어야 한다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1, id = SENDER)
	void 다른_출처의_요청에도_CORS_헤더가_붙는다() throws Exception {
		mockMvc.perform(request("/things", null, THING).header("Origin", "http://localhost:5173"))
				.andExpect(status().isBadRequest())
				.andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
		mockMvc.perform(request("/things", key, THING).header("Origin", "http://localhost:5173"))
				.andExpect(status().isCreated())
				.andExpect(header().string("Access-Control-Expose-Headers",
						org.hamcrest.Matchers.containsString("Idempotency-Replayed")));
		// 다시 돌려주는 응답은 필터가 쓴다. 그 응답도 다른 출처에서 읽을 수 있어야 한다.
		expectSecurityHeaders(mockMvc.perform(request("/things", key, THING).header("Origin", "http://localhost:5173"))
				.andExpect(status().isCreated())
				.andExpect(header().string("Idempotency-Replayed", "true"))
				.andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
				.andExpect(header().string("Access-Control-Expose-Headers",
						org.hamcrest.Matchers.containsString("Idempotency-Replayed"))));
	}

	// 필터가 직접 쓴 응답에도 보안 필터가 붙이는 헤더가 있어야 한다.
	// 예외로 던져 맨 앞의 필터가 응답을 비우게 하면 이 헤더들이 사라진다.
	private static ResultActions expectSecurityHeaders(ResultActions result) throws Exception {
		return result.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(header().string("X-Frame-Options", "DENY"))
				.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
	}

	private ResultActions send(String path, String idempotencyKey, String json) throws Exception {
		return mockMvc.perform(request(path, idempotencyKey, json));
	}

	private MockHttpServletRequestBuilder request(String path, String idempotencyKey, String json) throws Exception {
		MockHttpServletRequestBuilder request = post(BASE + path).with(csrfToken(mockMvc));
		if (idempotencyKey != null) {
			request.header("Idempotency-Key", idempotencyKey);
		}
		if (json != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(json);
		}
		return request;
	}

	private static WithJeyUser staff(long id) {
		return new WithJeyUser() {
			@Override
			public Class<WithJeyUser> annotationType() {
				return WithJeyUser.class;
			}

			@Override
			public UserRole role() {
				return UserRole.STAFF;
			}

			@Override
			public long id() {
				return id;
			}

			@Override
			public long campusId() {
				return 1;
			}

			@Override
			public long partnerId() {
				return WithJeyUser.NONE;
			}
		};
	}

}
