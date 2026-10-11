package com.jey.core.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

import com.jey.core.auth.api.CurrentActor;
import com.jey.core.shared.Actor;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.CommonErrorCode;
import com.jey.core.web.IdempotencyStore.Claim;
import com.jey.core.web.IdempotencyStore.StoredResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * {@code Idempotency-Key}가 필요한 POST를 한 번만 실행한다(ADR-0027).
 *
 * <p>같은 계정이 같은 키로 같은 요청을 다시 보내면 컨트롤러를 실행하지 않고 처음의 응답을 그대로 돌려준다.
 * 응답을 받지 못한 화면이 다시 보내도 일이 두 번 일어나지 않는다.
 *
 * <p>끝난 요청의 응답은 2xx와 4xx를 보관한다. 서버 오류(5xx)와 "잠시 후 다시"(429)는 보관하지 않는다. 같은 키로 다시 시도할 수 있어야 한다.
 * 키를 확인하지 못하면(저장소에 닿지 못함) 요청을 실행하지 않는다.
 *
 * <p>보안 필터 뒤에서 돈다. 키를 계정별로 보기 때문에 누가 보냈는지 알아야 한다.
 * 여기서 던진 예외는 맨 앞의 {@link FilterExceptionFilter}가 Problem Details로 바꾼다.
 */
@Component
class IdempotencyFilter extends OncePerRequestFilter implements Ordered {

	static final String KEY_HEADER = "Idempotency-Key";

	/** 처음의 응답을 다시 돌려준 것임을 알린다. */
	static final String REPLAYED_HEADER = "Idempotency-Replayed";

	// 내용이 같은지 가리려고 본문을 통째로 읽는다. 이 장치가 붙는 요청은 작은 JSON이다.
	static final int MAX_BODY_BYTES = 1024 * 1024;

	private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

	// UUID를 받는다. 화면이 다른 형식을 쓰더라도 헤더와 로그에 문제가 없는 글자만 받는다.
	private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{8,128}");

	private final IdempotentEndpoints endpoints;

	private final IdempotencyStore store;

	private final CurrentActor currentActor;

	IdempotencyFilter(IdempotentEndpoints endpoints, IdempotencyStore store, CurrentActor currentActor) {
		this.endpoints = endpoints;
		this.store = store;
		this.currentActor = currentActor;
	}

	// 보안 필터(-100)보다 뒤여야 로그인 정보를 읽을 수 있다.
	@Override
	public int getOrder() {
		return 0;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !"POST".equals(request.getMethod());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (!endpoints.requiresKey(request)) {
			chain.doFilter(request, response);
			return;
		}
		String key = request.getHeader(KEY_HEADER);
		if (key == null || !KEY.matcher(key).matches()) {
			throw new BusinessException(CommonErrorCode.IDEMPOTENCY_KEY_REQUIRED);
		}
		// 로그인하지 않은 요청은 보안 필터가 먼저 막는다. 여기까지 왔는데 계정을 모르면 설정이 어긋난 것이다.
		if (!(currentActor.get() instanceof Actor.User account)) {
			throw new IllegalStateException("로그인한 계정 없이 멱등 키를 쓸 수 없다. 로그인이 필요한 경로에만 @Idempotent를 붙인다");
		}
		byte[] body = readBody(request);
		switch (store.claim(account, key, fingerprint(request, body))) {
			case Claim.Mismatch mismatch -> throw new BusinessException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
			case Claim.InProgress inProgress -> throw new BusinessException(CommonErrorCode.IDEMPOTENCY_IN_PROGRESS);
			case Claim.Completed completed -> replay(response, completed.response());
			case Claim.Acquired acquired ->
				execute(new CachedBodyRequest(request, body), response, chain, account, key, acquired);
		}
	}

	private void execute(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
			Actor.User account, String key, Claim.Acquired claim) throws ServletException, IOException {
		ContentCachingResponseWrapper captured = new ContentCachingResponseWrapper(response);
		try {
			chain.doFilter(request, captured);
		}
		catch (IOException | ServletException | RuntimeException | Error failure) {
			// 무슨 일이 일어났는지 모른다. 같은 키로 다시 시도할 수 있게 한다.
			release(account, key, claim);
			throw failure;
		}
		byte[] body = captured.getContentAsByteArray();
		if (isStorable(captured.getStatus(), body)) {
			complete(account, key, claim, new StoredResponse(captured.getStatus(), captured.getContentType(), body));
		}
		else {
			release(account, key, claim);
		}
		captured.copyBodyToResponse();
	}

	// 서버 오류와 "잠시 후 다시"는 같은 키로 다시 시도할 수 있어야 한다. 보관하면 보관 기간 내내 같은 답이 나간다.
	// 본문 없는 오류는 여기서 본문을 보지 못한 것이다(컨테이너의 오류 경로로 넘어간 응답). 빈 응답을 보관하지 않는다.
	private static boolean isStorable(int status, byte[] body) {
		if (status >= 500 || status == HttpStatus.TOO_MANY_REQUESTS.value()) {
			return false;
		}
		return status < 400 || body.length > 0;
	}

	private static void replay(HttpServletResponse response, StoredResponse stored) throws IOException {
		response.setStatus(stored.status());
		if (stored.contentType() != null) {
			response.setContentType(stored.contentType());
		}
		response.setHeader(REPLAYED_HEADER, "true");
		response.setContentLength(stored.body().length);
		response.getOutputStream().write(stored.body());
	}

	// 보관하지 못해도 요청의 결과를 바꾸지 않는다. 일은 이미 끝났다.
	// 보관하지 못한 키는 임대가 끝난 뒤 다시 오면 한 번 더 실행된다.
	private void complete(Actor.User account, String key, Claim.Acquired claim, StoredResponse response) {
		try {
			store.complete(account, key, claim, response);
		}
		catch (RuntimeException ex) {
			log.error("멱등 키의 응답을 보관하지 못했다. 임대가 끝난 뒤 같은 키가 오면 다시 실행된다: accountId={} status={}",
					account.accountId(), response.status(), ex);
		}
	}

	// 풀지 못해도 임대가 끝나면 풀린다. 그때까지 같은 키는 "처리 중"으로 거부된다.
	private void release(Actor.User account, String key, Claim.Acquired claim) {
		try {
			store.release(account, key, claim);
		}
		catch (RuntimeException ex) {
			log.error("멱등 키의 선점을 풀지 못했다. 임대가 끝날 때까지 같은 키는 거부된다: accountId={}", account.accountId(), ex);
		}
	}

	private static byte[] readBody(HttpServletRequest request) throws IOException {
		byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
		if (body.length > MAX_BODY_BYTES) {
			throw new BusinessException(CommonErrorCode.BAD_REQUEST, "요청 본문이 너무 큽니다.");
		}
		return body;
	}

	// 같은 키로 다른 일을 시키는지 가린다. 주소(경로와 쿼리)와 본문이 모두 같아야 같은 요청이다.
	static String fingerprint(HttpServletRequest request, byte[] body) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			String query = (request.getQueryString() != null) ? request.getQueryString() : "";
			digest.update((request.getMethod() + "\n" + request.getRequestURI() + "\n" + query + "\n")
				.getBytes(StandardCharsets.UTF_8));
			digest.update(body);
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256을 쓸 수 없다", ex);
		}
	}

}
