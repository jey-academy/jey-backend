package com.jey.core.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * {@code Idempotency-Key}가 필요한 POST를 한 번만 실행한다(ADR-0027).
 *
 * <p>같은 계정이 같은 키로 같은 요청을 다시 보내면 컨트롤러를 실행하지 않고 처음의 응답을 그대로 돌려준다.
 * 응답을 받지 못한 화면이 다시 보내도 일이 두 번 일어나지 않는다.
 *
 * <p>끝난 요청의 응답은 2xx와 본문이 있는 4xx를 보관한다. 서버 오류(5xx)와 "잠시 후 다시"(429)는 보관하지 않는다. 같은 키로 다시 시도할 수 있어야 한다.
 * 보관하는 것은 상태와 본문 타입과 본문이다. 그 밖의 응답 헤더는 보관하지 않으므로 다시 돌려줄 때 빠진다.
 * 키를 확인하지 못하면(저장소에 닿지 못함) 요청을 실행하지 않는다.
 *
 * <p>JSON 본문(또는 본문 없음)을 받아 그 자리에서 응답하는 메서드에만 맞는다. 폼과 파일 올리기는 거부하고, 비동기로 응답하는 메서드에 붙이면 실패한다.
 *
 * <p>보안 필터 뒤에서 돈다. 키를 계정별로 보기 때문에 누가 보냈는지 알아야 한다. 컨트롤러의 접근 규칙(역할)보다는 앞이다.
 * 그래서 보관한 응답은 지금의 역할을 다시 묻지 않고 그 계정에 돌려준다.
 * 여기서 거부하는 요청은 그 자리에서 응답을 쓴다. 예외로 던지면 맨 앞의 {@link FilterExceptionFilter}가 응답을 비우면서 보안 헤더까지 지운다.
 */
@Component
class IdempotencyFilter extends OncePerRequestFilter implements Ordered {

	static final String KEY_HEADER = "Idempotency-Key";

	/** 처음의 응답을 다시 돌려준 것임을 알린다. */
	static final String REPLAYED_HEADER = "Idempotency-Replayed";

	static final int KEY_MIN_LENGTH = 8;

	static final int KEY_MAX_LENGTH = 128;

	/** 키의 형식. UUID를 받는다. 화면이 다른 형식을 쓰더라도 헤더와 로그에 문제가 없는 글자만 받는다. API 명세도 이 값을 쓴다. */
	static final String KEY_FORMAT = "[A-Za-z0-9_-]{" + KEY_MIN_LENGTH + "," + KEY_MAX_LENGTH + "}";

	// 내용이 같은지 가리려고 본문을 통째로 읽는다. 이 장치가 붙는 요청은 작은 JSON이다.
	static final int MAX_BODY_BYTES = 1024 * 1024;

	// 보관하는 응답의 크기. 응답은 요청마다 하나씩 보관 기간 내내 저장소에 남는다. 이 장치가 붙는 응답은 작은 JSON이다.
	static final int MAX_STORED_RESPONSE_BYTES = 256 * 1024;

	private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

	private static final Pattern KEY = Pattern.compile(KEY_FORMAT);

	private static final int MAX_LOGGED_URI_LENGTH = 200;

	private static final List<MediaType> JSON_ONLY = List.of(MediaType.APPLICATION_JSON);

	private final IdempotentEndpoints endpoints;

	private final IdempotencyStore store;

	private final CurrentActor currentActor;

	private final HandlerExceptionResolver exceptionResolver;

	// HandlerExceptionResolver 빈이 여러 개라 MVC의 합성 리졸버를 이름으로 지정한다.
	IdempotencyFilter(IdempotentEndpoints endpoints, IdempotencyStore store, CurrentActor currentActor,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
		this.endpoints = endpoints;
		this.store = store;
		this.currentActor = currentActor;
		this.exceptionResolver = exceptionResolver;
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
			reject(request, response, new BusinessException(CommonErrorCode.IDEMPOTENCY_KEY_REQUIRED));
			return;
		}
		// 로그인하지 않은 요청은 보안 필터가 먼저 막는다. 여기까지 왔는데 계정을 모르면 설정이 어긋난 것이다.
		if (!(currentActor.get() instanceof Actor.User account)) {
			throw new IllegalStateException("로그인한 계정 없이 멱등 키를 쓸 수 없다. 로그인이 필요한 경로에만 @Idempotent를 붙인다");
		}
		Attempt attempt = new Attempt(request, account, key);
		// 폼과 파일 올리기의 값은 컨테이너가 본문에서 꺼낸다. 여기서 본문을 먼저 읽으면 컨트롤러가 빈 값을 받는다.
		if (isFormOrMultipart(request)) {
			log.warn("멱등 키를 받는 경로에 폼이나 파일 올리기가 왔다. JSON 본문만 받는다: {}", attempt);
			reject(request, response, new HttpMediaTypeNotSupportedException(contentType(request), JSON_ONLY),
					HttpStatus.UNSUPPORTED_MEDIA_TYPE);
			return;
		}
		byte[] body = readBody(request);
		if (body == null) {
			reject(request, response, new BusinessException(CommonErrorCode.BAD_REQUEST, "요청 본문이 너무 큽니다."));
			return;
		}
		switch (store.claim(account, key, fingerprint(request, body))) {
			case Claim.Mismatch mismatch -> {
				// 화면이 시도마다 키를 새로 만들지 않거나, 값을 고친 뒤 같은 키로 다시 냈다.
				log.warn("이미 다른 내용에 쓴 멱등 키가 왔다: {}", attempt);
				reject(request, response, new BusinessException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED));
			}
			case Claim.InProgress inProgress -> {
				log.info("처리 중인 멱등 키가 다시 왔다: {}", attempt);
				reject(request, response, new BusinessException(CommonErrorCode.IDEMPOTENCY_IN_PROGRESS));
			}
			case Claim.Completed completed -> replay(response, completed.response());
			case Claim.Acquired acquired ->
				execute(new CachedBodyRequest(request, body), response, chain, attempt, acquired);
		}
	}

	private void execute(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
			Attempt attempt, Claim.Acquired claim) throws ServletException, IOException {
		ContentCachingResponseWrapper captured = new ContentCachingResponseWrapper(response);
		try {
			chain.doFilter(request, captured);
		}
		catch (IOException | ServletException | RuntimeException | Error failure) {
			// 무슨 일이 일어났는지 모른다. 같은 키로 다시 시도할 수 있게 한다.
			release(attempt, claim);
			throw failure;
		}
		// 비동기로 응답하는 메서드는 여기로 돌아온 뒤에 응답을 쓴다. 지금 보관하면 빈 응답이 보관된다.
		if (request.isAsyncStarted()) {
			release(attempt, claim);
			throw new IllegalStateException("@Idempotent는 비동기로 응답하는 메서드에 쓸 수 없다: " + attempt);
		}
		byte[] body = captured.getContentAsByteArray();
		if (body.length > MAX_STORED_RESPONSE_BYTES) {
			// 보관하지 않으면 같은 키로 다시 보낸 요청이 한 번 더 실행된다. 이렇게 큰 응답을 내는 경로에는 이 장치가 맞지 않는다.
			log.error("응답이 너무 커서 멱등 키로 보관하지 않는다: {} status={} bytes={}", attempt, captured.getStatus(),
					body.length);
			release(attempt, claim);
		}
		else if (isStorable(captured.getStatus(), body)) {
			complete(attempt, claim, new StoredResponse(captured.getStatus(), captured.getContentType(), body));
		}
		else {
			release(attempt, claim);
		}
		captured.copyBodyToResponse();
	}

	// 보관하는 것은 끝난 일의 결과(2xx)와 거부(4xx)다.
	// 서버 오류와 "잠시 후 다시"(429)는 같은 키로 다시 시도할 수 있어야 한다. 보관하면 보관 기간 내내 같은 답이 나간다.
	// 본문 없는 거부는 여기서 본문을 보지 못한 것이다(컨테이너의 오류 경로로 넘어간 응답). 빈 응답을 보관하지 않는다.
	// 다른 주소로 보내는 응답(3xx)은 헤더가 전부인데 헤더는 보관하지 않는다.
	static boolean isStorable(int status, byte[] body) {
		if (status >= 200 && status < 300) {
			return true;
		}
		return status >= 400 && status < 500 && status != HttpStatus.TOO_MANY_REQUESTS.value() && body.length > 0;
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

	// 컨트롤러에서 난 오류와 같은 형식(Problem Details)으로 응답한다. 응답을 비우지 않으므로 앞의 필터가 붙인 헤더가 그대로 나간다.
	private void reject(HttpServletRequest request, HttpServletResponse response, BusinessException rejection)
			throws IOException {
		reject(request, response, rejection, rejection.getErrorCode().status());
	}

	private void reject(HttpServletRequest request, HttpServletResponse response, Exception rejection,
			HttpStatusCode fallback) throws IOException {
		if (exceptionResolver.resolveException(request, response, null, rejection) == null) {
			log.error("멱등 키 필터의 거부를 Problem Details로 변환하지 못했다: {} {}", request.getMethod(),
					shorten(request.getRequestURI()), rejection);
			if (!response.isCommitted()) {
				response.sendError(fallback.value());
			}
		}
	}

	// 보관하지 못해도 요청의 결과를 바꾸지 않는다. 일은 이미 끝났다.
	// 보관하지 못한 키는 다시 오면 한 번 더 실행된다.
	private void complete(Attempt attempt, Claim.Acquired claim, StoredResponse response) {
		try {
			if (!store.complete(attempt.account(), attempt.key(), claim, response)) {
				// 까닭은 가리지 못한다. 처리가 임대보다 오래 걸렸을 수도 있고, 저장소가 비워졌을 수도 있다.
				log.error("멱등 키의 응답을 보관하지 못했다. 선점이 사라졌거나 다른 요청으로 넘어갔다. 같은 키가 다시 실행됐거나 실행될 수 있다: {} status={}",
						attempt, response.status());
			}
		}
		catch (RuntimeException ex) {
			log.error("멱등 키의 응답을 보관하지 못했다. 임대가 끝난 뒤 같은 키가 오면 다시 실행된다: {} status={}", attempt,
					response.status(), ex);
		}
	}

	// 풀지 못해도 임대가 끝나면 풀린다. 그때까지 같은 키는 "처리 중"으로 거부된다.
	private void release(Attempt attempt, Claim.Acquired claim) {
		try {
			if (!store.release(attempt.account(), attempt.key(), claim)) {
				// 풀 것이 없었다. 다시 시도하는 데는 지장이 없지만, 그사이 같은 키가 다시 실행됐을 수 있다.
				log.warn("멱등 키의 선점을 풀려고 했지만 이미 사라졌거나 다른 요청으로 넘어갔다: {}", attempt);
			}
		}
		catch (RuntimeException ex) {
			log.error("멱등 키의 선점을 풀지 못했다. 임대가 끝날 때까지 같은 키는 거부된다: {}", attempt, ex);
		}
	}

	private static boolean isFormOrMultipart(HttpServletRequest request) {
		String contentType = request.getContentType();
		if (contentType == null) {
			return false;
		}
		String type = contentType.trim().toLowerCase(Locale.ROOT);
		return type.startsWith("application/x-www-form-urlencoded") || type.startsWith("multipart/");
	}

	private static MediaType contentType(HttpServletRequest request) {
		try {
			return MediaType.parseMediaType(request.getContentType());
		}
		catch (InvalidMediaTypeException ex) {
			return null;
		}
	}

	/**
	 * @return 본문. 한도를 넘으면 {@code null}
	 */
	private static byte[] readBody(HttpServletRequest request) throws IOException {
		// 길이를 알려 준 요청은 읽기 전에 거른다. 알려 주지 않았거나 거짓으로 알려 준 요청은 읽으면서 거른다.
		if (request.getContentLengthLong() > MAX_BODY_BYTES) {
			return null;
		}
		byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
		return (body.length > MAX_BODY_BYTES) ? null : body;
	}

	// 같은 키로 다른 일을 시키는지 가린다. 주소(경로와 쿼리)와 본문이 모두 같아야 같은 요청이다.
	// 본문의 타입(Content-Type)은 넣지 않는다. 타입만 고쳐 다시 보내는 것도 값을 고친 것처럼 새 키를 써야 한다.
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

	private static String shorten(String uri) {
		return (uri != null && uri.length() > MAX_LOGGED_URI_LENGTH) ? uri.substring(0, MAX_LOGGED_URI_LENGTH) + "…"
				: uri;
	}

	// 키 하나로 온 요청 하나. 로그에는 모두 같은 모양으로 남긴다. 키의 원문과 쿼리 문자열은 남기지 않는다.
	private record Attempt(HttpServletRequest request, Actor.User account, String key) {

		@Override
		public String toString() {
			return request.getMethod() + " " + shorten(request.getRequestURI()) + " accountId=" + account.accountId()
					+ " key=" + IdempotencyStore.keyId(key);
		}

	}

}
