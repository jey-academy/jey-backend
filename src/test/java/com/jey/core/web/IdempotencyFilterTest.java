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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 저장소가 실패하는 경우처럼 실제 요청으로는 만들기 어려운 것을 가짜 보관소로 본다.
class IdempotencyFilterTest {

	private static final String KEY = "3f2b8c1e-0000-4000-8000-000000000001";

	private final FakeStore store = new FakeStore();

	private Actor actor = Actor.user(77);

	private boolean requiresKey = true;

	private final IdempotencyFilter filter = new IdempotencyFilter(new IdempotentEndpoints(null) {
		@Override
		boolean requiresKey(jakarta.servlet.http.HttpServletRequest request) {
			return requiresKey;
		}
	}, store, () -> actor);

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
	void 처리_중이거나_다른_내용이면_실행하지_않고_거부한다() {
		store.next = new Claim.InProgress();
		assertThatThrownBy(() -> filter.doFilter(request("{}"), new MockHttpServletResponse(), respond(201, "{}")))
				.isInstanceOfSatisfying(BusinessException.class,
						ex -> assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.IDEMPOTENCY_IN_PROGRESS));

		store.next = new Claim.Mismatch();
		assertThatThrownBy(() -> filter.doFilter(request("{}"), new MockHttpServletResponse(), respond(201, "{}")))
				.isInstanceOfSatisfying(BusinessException.class,
						ex -> assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_REUSED));

		assertThat(executed).hasValue(0);
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
	void 본문이_한도를_넘으면_키를_보기_전에_거부한다() {
		MockHttpServletRequest request = request("x".repeat(IdempotencyFilter.MAX_BODY_BYTES + 1));

		assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), respond(201, "{}")))
				.isInstanceOfSatisfying(BusinessException.class,
						ex -> assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.BAD_REQUEST));

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
		void complete(Actor.User account, String idempotencyKey, Claim.Acquired claim, StoredResponse response) {
			if (completeFailure != null) {
				throw completeFailure;
			}
			completed.add(response);
		}

		@Override
		void release(Actor.User account, String idempotencyKey, Claim.Acquired claim) {
			if (releaseFailure != null) {
				throw releaseFailure;
			}
			released++;
		}

	}

}
