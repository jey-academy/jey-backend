package com.jey.core.web;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

import com.jey.core.shared.BusinessException;
import com.jey.core.shared.CommonErrorCode;
import com.jey.core.shared.ErrorCode;
import com.jey.core.shared.RateLimitedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.Errors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import com.jey.core.shared.PhoneNumber;
import org.springframework.http.converter.HttpMessageNotReadableException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.exc.ValueInstantiationException;

@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	// 오류 응답 본문의 속성 이름. 오류 경로 컨트롤러(ProblemDetailsErrorController)도 같은 이름으로 싣는다.
	static final String CODE = "code";

	private static final String ERRORS = "errors";

	static final String REQUEST_ID = "requestId";

	private static final String BINDING_FAILURE_MESSAGE = "형식이 올바르지 않습니다.";

	private static final String PHONE_FORMAT_MESSAGE = "휴대전화 번호 형식이 아닙니다.";

	private static final int MAX_LOGGED_URI_LENGTH = 200;

	@ExceptionHandler(BusinessException.class)
	ResponseEntity<Object> handleBusiness(BusinessException ex, WebRequest request) {
		if (ex.getErrorCode().status() == HttpStatus.FORBIDDEN) {
			logDenied(ex, request);
		}
		HttpHeaders headers = new HttpHeaders();
		if (ex instanceof RateLimitedException limited) {
			// 언제 다시 보내면 되는지 알려 준다. 화면이 남은 시간을 보여 줄 수 있다.
			headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(limited.retryAfterSeconds()));
		}
		ProblemDetail problem = problem(ex.getErrorCode(), ex.getMessage());
		return handleExceptionInternal(ex, problem, headers, ex.getErrorCode().status(), request);
	}

	// SecurityConfig가 필터 단계에서 넘긴 예외도 아래 두 메서드로 온다.
	@ExceptionHandler(AuthenticationException.class)
	ResponseEntity<Object> handleAuthentication(AuthenticationException ex, WebRequest request) {
		return respond(ex, CommonErrorCode.UNAUTHENTICATED, null, request);
	}

	// 인증 예외 중에서도 이것은 "누구인지 모른다"가 아니라 "확인하다 실패했다"(계정 조회 중 DB 오류 등)이다.
	// 401로 응답하면 장애가 로그아웃처럼 보이고 원인도 로그에 남지 않는다.
	@ExceptionHandler(AuthenticationServiceException.class)
	ResponseEntity<Object> handleAuthenticationServiceFailure(AuthenticationServiceException ex, WebRequest request) {
		return respond(ex, CommonErrorCode.INTERNAL_ERROR, null, request);
	}

	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
		return respond(ex, CommonErrorCode.FORBIDDEN, null, request);
	}

	// 예외 메시지에 내부 정보(접속 주소 등)가 섞일 수 있어 응답에는 일반 문구만 내보낸다.
	@ExceptionHandler(Exception.class)
	ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
		return respond(ex, CommonErrorCode.INTERNAL_ERROR, null, request);
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		return handleExceptionInternal(ex, validationProblem(violations(ex.getBindingResult())), headers, status,
				request);
	}

	// 본문(JSON)의 값 하나를 읽지 못한 경우다: 형식이 틀린 전화번호, 고를 수 없는 값, 숫자 칸의 글자, 틀린 날짜.
	// 어느 칸인지 알면 그 칸의 검증 실패로 답한다. 폼이 그 칸 밑에 오류를 보여 줄 수 있다.
	// 본문이 JSON이 아니거나 모양이 통째로 틀려 칸을 알 수 없으면 요청 형식 오류(COMMON_BAD_REQUEST)로 둔다.
	@Override
	protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		FieldViolation unreadable = unreadableField(ex);
		if (unreadable == null) {
			return super.handleHttpMessageNotReadable(ex, headers, status, request);
		}
		return handleExceptionInternal(ex, validationProblem(List.of(unreadable)), headers, status, request);
	}

	// 문구는 고정이다. 변환 실패 문구에는 내부 타입 이름과 보낸 값이 들어 있다(전화번호가 응답에 실리면 안 된다).
	private static FieldViolation unreadableField(Throwable ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			Class<?> target = targetTypeOf(cause);
			if (target != null && cause instanceof DatabindException failure && !failure.getPath().isEmpty()) {
				return new FieldViolation(fieldOf(failure.getPath()),
						(target == PhoneNumber.class) ? PHONE_FORMAT_MESSAGE : BINDING_FAILURE_MESSAGE);
			}
		}
		return null;
	}

	// 값을 그 타입으로 바꾸다 실패한 것만 본다. 문법이 깨진 본문은 여기에 해당하지 않는다.
	private static Class<?> targetTypeOf(Throwable cause) {
		if (cause instanceof ValueInstantiationException instantiation) {
			return instantiation.getType().getRawClass();
		}
		if (cause instanceof MismatchedInputException mismatch) {
			return (mismatch.getTargetType() != null) ? mismatch.getTargetType() : Object.class;
		}
		return null;
	}

	// 검증 실패가 쓰는 표기와 같게 적는다: guardians[1].phone
	private static String fieldOf(List<JacksonException.Reference> path) {
		StringBuilder field = new StringBuilder();
		for (JacksonException.Reference step : path) {
			if (step.getPropertyName() != null) {
				if (!field.isEmpty()) {
					field.append('.');
				}
				field.append(step.getPropertyName());
			}
			else {
				field.append('[').append(step.getIndex()).append(']');
			}
		}
		return field.toString();
	}

	@Override
	protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		// 반환값 검증 실패는 입력이 아니라 서버 쪽 문제다.
		if (ex.isForReturnValue()) {
			return respond(ex, CommonErrorCode.INTERNAL_ERROR, null, request);
		}
		List<FieldViolation> errors = new ArrayList<>();
		ex.getParameterValidationResults().forEach(result -> {
			// @Valid 객체(@RequestBody 등)는 객체 안의 필드 이름을 쓰고, 단일 파라미터는 파라미터 이름을 쓴다.
			if (result instanceof Errors objectErrors) {
				errors.addAll(violations(objectErrors));
			}
			else {
				String parameterName = result.getMethodParameter().getParameterName();
				result.getResolvableErrors()
						.forEach(error -> errors.add(new FieldViolation(parameterName, error.getDefaultMessage())));
			}
		});
		ex.getCrossParameterValidationResults()
				.forEach(error -> errors.add(new FieldViolation(null, error.getDefaultMessage())));
		return handleExceptionInternal(ex, validationProblem(errors), headers, status, request);
	}

	// 모든 에러 응답이 지나는 마지막 지점.
	// code가 없으면 Spring이 만든 본문이다(404, 405, 본문 파싱 실패, ResponseStatusException 등).
	// HTTP 상태로 공통 코드를 붙이고, 프론트가 detail을 그대로 보여주므로 Spring 기본 영어 문구를 공통 코드의 문구로 바꾼다.
	// 요청 ID도 여기서 싣는다. 화면에서 본 오류를 서버 로그에서 찾는 데 쓴다 (ADR-0028).
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		// Spring이 500으로 매핑하는 예외(응답 직렬화 실패 등)는 catch-all을 거치지 않으므로 여기서 한 번에 남긴다.
		if (statusCode.is5xxServerError()) {
			log.error("서버 오류 응답: status={} {}", statusCode.value(), request.getDescription(false), ex);
		}
		ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
		if (response != null && response.getBody() instanceof ProblemDetail problem) {
			if (!hasCode(problem)) {
				CommonErrorCode errorCode = CommonErrorCode.fromStatus(statusCode);
				problem.setDetail(errorCode.message());
				problem.setProperty(CODE, errorCode.code());
			}
			String requestId = RequestIdFilter.of(request);
			if (requestId != null) {
				problem.setProperty(REQUEST_ID, requestId);
			}
		}
		return response;
	}

	private ResponseEntity<Object> respond(Exception ex, ErrorCode errorCode, String detail, WebRequest request) {
		ProblemDetail problem = problem(errorCode, detail != null ? detail : errorCode.message());
		return handleExceptionInternal(ex, problem, new HttpHeaders(), errorCode.status(), request);
	}

	// 로그인은 했지만 허용되지 않은 요청이다(역할, 지점 범위 등). 누가 무엇을 하려다 막혔는지 남긴다.
	// 이름은 계정 ID다. 아이디와 실명은 남기지 않는다. 권한은 세션에 든 값이라, 역할을 바꾼 뒤에도 세션이 살아 있는 경우를 가려낼 수 있다.
	private static void logDenied(BusinessException ex, WebRequest request) {
		Principal principal = request.getUserPrincipal();
		Object authorities = (principal instanceof Authentication authentication) ? authentication.getAuthorities()
				: List.of();
		String target = (request instanceof ServletWebRequest servletRequest)
				? servletRequest.getRequest().getMethod() + " " + shorten(servletRequest.getRequest().getRequestURI())
				: request.getDescription(false);
		log.warn("접근 거부: code={} account={} authorities={} {}", ex.getErrorCode().code(),
				(principal != null) ? principal.getName() : null, authorities, target);
	}

	private static String shorten(String uri) {
		return (uri != null && uri.length() > MAX_LOGGED_URI_LENGTH) ? uri.substring(0, MAX_LOGGED_URI_LENGTH) + "…"
				: uri;
	}

	private static List<FieldViolation> violations(Errors errors) {
		List<FieldViolation> violations = new ArrayList<>();
		// 변환 실패 문구에는 내부 타입 이름과 입력값이 들어 있어 고정 문구로 바꾼다.
		errors.getFieldErrors().forEach(error -> violations.add(new FieldViolation(error.getField(),
				error.isBindingFailure() ? BINDING_FAILURE_MESSAGE : error.getDefaultMessage())));
		errors.getGlobalErrors().forEach(error -> violations.add(new FieldViolation(null, error.getDefaultMessage())));
		return violations;
	}

	private static ProblemDetail validationProblem(List<FieldViolation> errors) {
		ProblemDetail problem = problem(CommonErrorCode.VALIDATION_FAILED, CommonErrorCode.VALIDATION_FAILED.message());
		problem.setProperty(ERRORS, errors);
		return problem;
	}

	private static ProblemDetail problem(ErrorCode errorCode, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(errorCode.status(), detail);
		problem.setProperty(CODE, errorCode.code());
		return problem;
	}

	private static boolean hasCode(ProblemDetail problem) {
		return problem.getProperties() != null && problem.getProperties().containsKey(CODE);
	}

	/**
	 * 응답 {@code errors} 배열의 원소. 컴포넌트 이름이 그대로 JSON 필드 이름이 된다.
	 *
	 * @param field 오류가 난 필드나 파라미터 이름. 특정 필드에 묶이지 않는 오류(객체 수준 제약 등)는 {@code null}
	 */
	record FieldViolation(String field, String message) {

		FieldViolation {
			if (message == null) {
				message = CommonErrorCode.VALIDATION_FAILED.message();
			}
		}

	}

}
