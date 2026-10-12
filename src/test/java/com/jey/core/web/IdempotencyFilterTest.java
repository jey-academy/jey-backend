package com.jey.core.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.jey.core.shared.Actor;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.CommonErrorCode;
import com.jey.core.web.IdempotencyStore.Claim;
import com.jey.core.web.IdempotencyStore.StoredResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 저장소가 실패하는 경우처럼 실제 요청으로는 만들기 어려운 것을 가짜 보관소로 본다.
@ExtendWith(OutputCaptureExtension.class)
class IdempotencyFilterTest {

	private static final String KEY = "3f2b8c1e-0000-4000-8000-000000000001";

	private final FakeStore store = new FakeStore();

	private Actor actor = Actor.user(77);

	private boolean requiresKey = true;

	// 필터가 거부하면서 넘긴 예외. 실제로는 MVC의 예외 처리기가 Problem Details로 응답을 쓴다.
	private final List<Exception> rejections = new ArrayList<>();

	private boolean resolves = true;

	private final HandlerExceptionResolver resolver = (request, response, handler, ex) -> {
		rejections.add(ex);
		if (!resolves) {
			return null;
		}
		response.setStatus((ex instanceof BusinessException business) ? business.getErrorCode().status().value() : 415);
		return new ModelAndView();
	};

	private final IdempotencyFilter filter = new IdempotencyFilter(new IdempotentEndpoints(null) {
		@Override
		boolean requiresKey(jakarta.servlet.http.HttpServletRequest request) {
			return requiresKey;
		}
	}, store, () -> actor, resolver);

	private final AtomicInteger executed = new AtomicInteger();

	// "Redis가 안 될 때 업무가 멈추지 않게" 그냥 실행하면, 다시 보낸 요청이 조용히 두 번 실행된다.
	@Test
	void 키를_확인하지_못하면_실행하지_않는다() {
		store.claimFailure = new IllegalStateException("Redis에 닿지 못했다");

		assertThatThrownBy(() -> filter.doFilter(request("{}"), new MockHttpServletResponse(), respond(201, "{}")))
				.isSameAs(store.claimFailure);

		assertThat(executed).hasValue(0);
	}

	// 일은 이미 끝났다. 여기서 실패로 답하면 화면이 다시 보내고, 보관한 것이 없으니 한 번 더 실행된다.
	@Test
	void 응답을_보관하지_못해도_응답은_그대로_나간다() throws Exception {
		store.completeFailure = new IllegalStateException("Redis에 닿지 못했다");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request("{}"), response, respond(201, "{\"id\":1}"));

		assertThat(response.getStatus()).isEqualTo(201);
		assertThat(response.getContentAsString()).isEqualTo("{\"id\":1}");
	}

	@Test
	void 끝난_요청의_응답을_상태와_타입과_본문_그대로_보관한다() throws Exception {
		filter.doFilter(request("{}"), new MockHttpServletResponse(), respond(201, "{\"이름\":\"김철수\"}"));

		assertThat(store.completed).hasSize(1);
		StoredResponse stored = store.completed.getFirst();
		assertThat(stored.status()).isEqualTo(201);
		assertThat(stored.contentType()).startsWith("application/json");
		assertThat(new String(stored.body(), StandardCharsets.UTF_8)).isEqualTo("{\"이름\":\"김철수\"}");
		assertThat(store.released).isZero();
	}

	// 저장소가 "넣지 않았다"고 답한 경우다(임대가 끝났거나 저장소가 비워졌다). 예외가 아니라서 놓치기 쉽다.
	@Test
	void 선점이_사라져_보관하지_못해도_응답은_그대로_나가고_로그를_남긴다(CapturedOutput output) throws Exception {
		store.completeResult = false;
		store.releaseResult = false;
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request("{}"), response, respond(201, "{\"id\":1}"));
		filter.doFilter(request("{}"), new MockHttpServletResponse(), respond(500, "{}"));

		assertThat(response.getStatus()).isEqualTo(201);
		assertThat(response.getContentAsString()).isEqualTo("{\"id\":1}");
		// 어느 요청인지 찾을 수 있어야 한다. 키의 원문은 남기지 않는다.
		assertThat(output.getOut()).contains("멱등 키의 응답을 보관하지 못했다")
				.contains("멱등 키의 선점을 풀려고 했지만")
				.contains("POST /api/v1/things accountId=77 key=" + IdempotencyStore.keyId(KEY))
				.doesNotContain(KEY);
	}

	@Test
	void 서버_오류와_횟수_한도의_응답은_보관하지_않고_선점을_푼다() throws Exception {
		filter.doFilter(request("{}"), new MockHttpServletResponse(), respond(500, "{\"code\":\"COMMON_INTERNAL_ERROR\"}"));
		filter.doFilter(request("{}"), new MockHttpServletResponse(), respond(503, "{}"));
		filter.doFilter(request("{}"), new MockHttpServletResponse(), respond(429, "{\"code\":\"X\"}"));

		assertThat(store.completed).isEmpty();
		assertThat(store.released).isEqualTo(3);
	}

	// 본문이 없는 오류는 여기서 본문을 보지 못한 것이다. 빈 응답을 보관하면 다시 보낸 쪽이 이유 없는 오류를 받는다.
	@Test
	void 본문이_없는_오류_응답은_보관하지_않는다() throws Exception {
		filter.doFilter(request("{}"), new MockHttpServletResponse(), respond(404, ""));
		filter.doFilter(request("{}"), new MockHttpServletResponse(), respond(204, ""));

		assertThat(store.completed).extracting(StoredResponse::status).containsExactly(204);
		assertThat(store.released).isEqualTo(1);
	}

	// 다른 주소로 보내는 응답은 헤더(Location)가 전부인데 헤더는 보관하지 않는다. 보관하면 다시 보낸 쪽이 갈 곳 없는 302를 받는다.
	@Test
	void 성공과_본문_있는_거부만_보관한다() throws Exception {
		filter.doFilter(request("{}"), new MockHttpServletResponse(), respond(302, ""));
		filter.doFilter(request("{}"), new MockHttpServletResponse(), respond(304, "{}"));

		assertThat(store.completed).isEmpty();
		assertThat(store.released).isEqualTo(2);
		byte[] some = "{}".getBytes(StandardCharsets.UTF_8);
		byte[] none = new byte[0];
		assertThat(IdempotencyFilter.isStorable(200, none)).isTrue();
		assertThat(IdempotencyFilter.isStorable(299, some)).isTrue();
		assertThat(IdempotencyFilter.isStorable(199, some)).isFalse();
		assertThat(IdempotencyFilter.isStorable(300, some)).isFalse();
		assertThat(IdempotencyFilter.isStorable(399, some)).isFalse();
		assertThat(IdempotencyFilter.isStorable(400, some)).isTrue();
		assertThat(IdempotencyFilter.isStorable(400, none)).isFalse();
		assertThat(IdempotencyFilter.isStorable(428, some)).isTrue();
		assertThat(IdempotencyFilter.isStorable(429, some)).isFalse();
		assertThat(IdempotencyFilter.isStorable(499, some)).isTrue();
		assertThat(IdempotencyFilter.isStorable(500, some)).isFalse();
	}

	// 비동기로 응답하는 메서드는 필터로 돌아온 뒤에 응답을 쓴다. 그대로 두면 빈 200이 보관되고 다시 보낸 쪽이 그것을 받는다.
	@Test
	void 비동기로_응답하는_메서드에_붙어_있으면_보관하지_않고_실패한다() {
		MockHttpServletRequest request = request("{}");
		request.setAsyncSupported(true);
		FilterChain async = (req, res) -> req.startAsync();

		assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), async))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("비동기");

		assertThat(store.completed).isEmpty();
		assertThat(store.released).isEqualTo(1);
	}

	// 폼과 파일 올리기의 값은 컨테이너가 본문에서 꺼낸다. 필터가 본문을 먼저 읽으면 컨트롤러가 빈 값을 받는다.
	@Test
	void 폼이나_파일_올리기로_온_요청은_실행하지_않고_거부한다() throws Exception {
		for (String contentType : List.of("application/x-www-form-urlencoded", "Application/X-WWW-Form-Urlencoded;charset=UTF-8",
				"multipart/form-data; boundary=x", "multipart/mixed")) {
			MockHttpServletRequest request = request("name=a");
			request.setContentType(contentType);
			MockHttpServletResponse response = new MockHttpServletResponse();

			filter.doFilter(request, response, respond(201, "{}"));

			assertThat(response.getStatus()).as(contentType).isEqualTo(415);
		}
		assertThat(rejections).hasSize(4).allMatch(HttpMediaTypeNotSupportedException.class::isInstance);
		assertThat(store.claims).isZero();
		assertThat(executed).hasValue(0);
	}

	// 예외로 던지면 맨 앞의 필터가 응답을 비우면서 앞의 필터들이 붙인 헤더(보안 헤더)까지 지운다. 그 자리에서 응답을 쓴다.
	@Test
	void 거부할_때는_던지지_않고_그_자리에서_응답을_써서_앞에서_붙인_헤더가_남는다() throws Exception {
		MockHttpServletRequest request = request("{}");
		request.removeHeader("Idempotency-Key");
		MockHttpServletResponse response = new MockHttpServletResponse();
		response.setHeader("X-Content-Type-Options", "nosniff");

		filter.doFilter(request, response, respond(201, "{}"));

		assertThat(response.getStatus()).isEqualTo(400);
		assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
		assertThat(rejections).singleElement()
				.isInstanceOfSatisfying(BusinessException.class,
						ex -> assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_REQUIRED));
		assertThat(executed).hasValue(0);
	}

	// 변환기가 응답을 쓰지 못하면 상태가 200으로 남아 거부가 성공처럼 보인다.
	@Test
	void 거부를_응답으로_바꾸지_못하면_실패_상태로_응답한다() throws Exception {
		resolves = false;
		store.next = new Claim.Mismatch();
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request("{}"), response, respond(201, "{}"));

		assertThat(response.getStatus()).isEqualTo(422);
		assertThat(executed).hasValue(0);
	}

	// 응답은 요청마다 하나씩 24시간 동안 저장소에 남는다. 크기에 한도가 없으면 저장소가 찬다.
	@Test
	void 너무_큰_응답은_보관하지_않고_그대로_내보낸다() throws Exception {
		String huge = "x".repeat(IdempotencyFilter.MAX_STORED_RESPONSE_BYTES + 1);
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request("{}"), response, respond(200, huge));

		assertThat(store.completed).isEmpty();
		assertThat(store.released).isEqualTo(1);
		assertThat(response.getContentAsByteArray()).hasSize(IdempotencyFilter.MAX_STORED_RESPONSE_BYTES + 1);
	}

	// 길이를 알려 준 요청은 본문을 읽지 않고 거른다.
	@Test
	void 길이가_한도를_넘는다고_알려_온_요청은_읽지_않고_거부한다() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/things") {
			@Override
			public long getContentLengthLong() {
				return IdempotencyFilter.MAX_BODY_BYTES + 1L;
			}

			@Override
			public jakarta.servlet.ServletInputStream getInputStream() {
				throw new AssertionError("본문을 읽으면 안 된다");
			}
		};
		request.addHeader("Idempotency-Key", KEY);

		filter.doFilter(request, new MockHttpServletResponse(), respond(201, "{}"));

		assertRejected(CommonErrorCode.BAD_REQUEST);
		assertThat(store.claims).isZero();
	}

	// 길이를 알리지 않고 보내는 요청(chunked)이 있다. 읽은 뒤에도 재지 않으면 한도에서 잘린 본문이 컨트롤러로 넘어간다.
	@Test
	void 길이를_알리지_않은_요청도_한도를_넘으면_거부한다() throws Exception {
		filter.doFilter(unknownLength(IdempotencyFilter.MAX_BODY_BYTES + 1), new MockHttpServletResponse(),
				respond(201, "{}"));

		assertRejected(CommonErrorCode.BAD_REQUEST);
		assertThat(store.claims).isZero();
		assertThat(executed).hasValue(0);
	}

	@Test
	void 한도와_꼭_같은_크기의_본문은_잘리지_않고_넘어간다() throws Exception {
		List<Integer> seen = new ArrayList<>();
		FilterChain reading = (request, response) -> {
			seen.add(request.getInputStream().readAllBytes().length);
			((HttpServletResponse) response).setStatus(204);
		};

		filter.doFilter(unknownLength(IdempotencyFilter.MAX_BODY_BYTES), new MockHttpServletResponse(), reading);

		assertThat(seen).containsExactly(IdempotencyFilter.MAX_BODY_BYTES);
		assertThat(rejections).isEmpty();
	}

	@Test
	void 선점을_풀지_못해도_응답은_그대로_나간다() throws Exception {
		store.releaseFailure = new IllegalStateException("Redis에 닿지 못했다");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request("{}"), response, respond(500, "{\"code\":\"COMMON_INTERNAL_ERROR\"}"));

		assertThat(response.getStatus()).isEqualTo(500);
		assertThat(response.getContentAsString()).contains("COMMON_INTERNAL_ERROR");
	}

	// 뒤에서 예외가 그대로 올라온 경우다. 무슨 일이 일어났는지 모르므로 다시 시도할 수 있게 한다.
	@Test
	void 처리하다_예외가_나면_선점을_풀고_그_예외를_그대로_던진다() {
		IOException failure = new IOException("연결이 끊겼다");
		FilterChain failing = (request, response) -> {
			throw failure;
		};

		assertThatThrownBy(() -> filter.doFilter(request("{}"), new MockHttpServletResponse(), failing))
				.isSameAs(failure);

		assertThat(store.released).isEqualTo(1);
		assertThat(store.completed).isEmpty();
	}

	@Test
	void 보관한_응답이_있으면_실행하지_않고_그대로_쓴다() throws Exception {
		store.next = new Claim.Completed(
				new StoredResponse(201, "application/json", "{\"id\":7}".getBytes(StandardCharsets.UTF_8)));
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request("{}"), response, respond(500, "실행되면 안 된다"));

		assertThat(executed).hasValue(0);
		assertThat(response.getStatus()).isEqualTo(201);
		assertThat(response.getContentType()).isEqualTo("application/json");
		assertThat(response.getContentAsString()).isEqualTo("{\"id\":7}");
		assertThat(response.getHeader("Idempotency-Replayed")).isEqualTo("true");
	}

	@Test
	void 처리_중이거나_다른_내용이면_실행하지_않고_거부한다(CapturedOutput output) throws Exception {
		store.next = new Claim.InProgress();
		MockHttpServletResponse inProgress = new MockHttpServletResponse();
		filter.doFilter(request("{}"), inProgress, respond(201, "{}"));
		assertRejected(CommonErrorCode.IDEMPOTENCY_IN_PROGRESS);
		assertThat(inProgress.getStatus()).isEqualTo(409);

		rejections.clear();
		store.next = new Claim.Mismatch();
		MockHttpServletResponse mismatch = new MockHttpServletResponse();
		filter.doFilter(request("{}"), mismatch, respond(201, "{}"));
		assertRejected(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
		assertThat(mismatch.getStatus()).isEqualTo(422);

		assertThat(executed).hasValue(0);
		// 같은 키에 다른 내용이 오는 것은 화면이 키를 잘못 다루고 있다는 뜻이다. 서버에서도 보여야 한다.
		String target = "POST /api/v1/things accountId=77 key=" + IdempotencyStore.keyId(KEY);
		assertThat(output.getOut()).contains("처리 중인 멱등 키가 다시 왔다: " + target)
				.contains("이미 다른 내용에 쓴 멱등 키가 왔다: " + target);
	}

	// 컨트롤러는 필터가 이미 읽은 본문을 다시 읽을 수 있어야 한다.
	@Test
	void 뒤의_컨트롤러는_본문을_그대로_읽는다() throws Exception {
		List<String> seen = new ArrayList<>();
		FilterChain reading = (request, response) -> {
			seen.add(new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
			((HttpServletResponse) response).setStatus(204);
		};

		filter.doFilter(request("{\"name\":\"김철수\"}"), new MockHttpServletResponse(), reading);

		assertThat(seen).containsExactly("{\"name\":\"김철수\"}");
	}

	@Test
	void 본문이_한도를_넘으면_키를_보기_전에_거부한다() throws Exception {
		MockHttpServletRequest request = request("x".repeat(IdempotencyFilter.MAX_BODY_BYTES + 1));

		filter.doFilter(request, new MockHttpServletResponse(), respond(201, "{}"));

		assertRejected(CommonErrorCode.BAD_REQUEST);

		assertThat(store.claims).isZero();
		assertThat(executed).hasValue(0);
	}

	// 로그인이 필요 없는 경로에 표시를 붙인 실수다. 계정 없이 키를 보면 모든 사람이 키를 같이 쓰게 된다.
	@Test
	void 로그인한_계정이_없으면_실행하지_않는다() {
		actor = Actor.none();

		assertThatThrownBy(() -> filter.doFilter(request("{}"), new MockHttpServletResponse(), respond(201, "{}")))
				.isInstanceOf(IllegalStateException.class);

		assertThat(executed).hasValue(0);
	}

	@Test
	void 키를_받지_않는_경로와_POST가_아닌_요청은_그대로_지나간다() throws Exception {
		requiresKey = false;
		filter.doFilter(request("{}"), new MockHttpServletResponse(), respond(201, "{}"));
		requiresKey = true;
		MockHttpServletRequest put = request("{}");
		put.setMethod("PUT");
		put.removeHeader("Idempotency-Key");
		filter.doFilter(put, new MockHttpServletResponse(), respond(200, "{}"));

		assertThat(executed).hasValue(2);
		assertThat(store.claims).isZero();
	}

	@Test
	void 지문은_주소와_본문이_같을_때만_같다() {
		byte[] body = "{\"name\":\"a\"}".getBytes(StandardCharsets.UTF_8);
		MockHttpServletRequest base = new MockHttpServletRequest("POST", "/api/v1/students");
		String fingerprint = IdempotencyFilter.fingerprint(base, body);

		assertThat(IdempotencyFilter.fingerprint(new MockHttpServletRequest("POST", "/api/v1/students"), body))
				.isEqualTo(fingerprint)
				.hasSize(64);
		assertThat(IdempotencyFilter.fingerprint(base, "{\"name\":\"b\"}".getBytes(StandardCharsets.UTF_8)))
				.isNotEqualTo(fingerprint);
		assertThat(IdempotencyFilter.fingerprint(new MockHttpServletRequest("POST", "/api/v1/classes"), body))
				.isNotEqualTo(fingerprint);
		MockHttpServletRequest withQuery = new MockHttpServletRequest("POST", "/api/v1/students");
		withQuery.setQueryString("dryRun=true");
		assertThat(IdempotencyFilter.fingerprint(withQuery, body)).isNotEqualTo(fingerprint);
		// 경로와 본문의 경계가 섞이지 않는다.
		assertThat(IdempotencyFilter.fingerprint(new MockHttpServletRequest("POST", "/a"), "b".getBytes()))
				.isNotEqualTo(IdempotencyFilter.fingerprint(new MockHttpServletRequest("POST", "/ab"), new byte[0]));
	}

	private void assertRejected(CommonErrorCode code) {
		assertThat(rejections).singleElement()
				.isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getErrorCode()).isEqualTo(code));
	}

	// Content-Length 없이 온 요청.
	private static MockHttpServletRequest unknownLength(int bytes) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/things") {
			@Override
			public long getContentLengthLong() {
				return -1;
			}

			@Override
			public int getContentLength() {
				return -1;
			}
		};
		request.addHeader("Idempotency-Key", KEY);
		request.setContentType("application/json");
		request.setContent(new byte[bytes]);
		return request;
	}

	private static MockHttpServletRequest request(String body) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/things");
		request.addHeader("Idempotency-Key", KEY);
		request.setContentType("application/json");
		request.setContent(body.getBytes(StandardCharsets.UTF_8));
		return request;
	}

	private FilterChain respond(int status, String body) {
		return (request, response) -> {
			executed.incrementAndGet();
			HttpServletResponse http = (HttpServletResponse) response;
			http.setStatus(status);
			if (!body.isEmpty()) {
				http.setContentType("application/json;charset=UTF-8");
				http.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
			}
		};
	}

	// 무엇이 불렸는지 적어 두고, 시키면 실패한다.
	private static final class FakeStore extends IdempotencyStore {

		final List<StoredResponse> completed = new ArrayList<>();

		int claims;

		int released;

		Claim next = new Claim.Acquired("표식");

		RuntimeException claimFailure;

		RuntimeException completeFailure;

		RuntimeException releaseFailure;

		boolean completeResult = true;

		boolean releaseResult = true;

		FakeStore() {
			super(null, new IdempotencyProperties(Duration.ofHours(24), Duration.ofMinutes(2)));
		}

		@Override
		Claim claim(Actor.User account, String idempotencyKey, String fingerprint) {
			if (claimFailure != null) {
				throw claimFailure;
			}
			claims++;
			return next;
		}

		@Override
		boolean complete(Actor.User account, String idempotencyKey, Claim.Acquired claim, StoredResponse response) {
			if (completeFailure != null) {
				throw completeFailure;
			}
			if (completeResult) {
				completed.add(response);
			}
			return completeResult;
		}

		@Override
		boolean release(Actor.User account, String idempotencyKey, Claim.Acquired claim) {
			if (releaseFailure != null) {
				throw releaseFailure;
			}
			if (releaseResult) {
				released++;
			}
			return releaseResult;
		}

	}

}
