package com.jey.core.web;

import java.util.ArrayList;
import java.util.List;

import com.jey.core.shared.BusinessException;
import com.jey.core.shared.CommonErrorCode;
import com.jey.core.shared.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.Errors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	private static final String CODE = "code";

	private static final String ERRORS = "errors";

	private static final String BINDING_FAILURE_MESSAGE = "형식이 올바르지 않습니다.";

	@ExceptionHandler(BusinessException.class)
	ResponseEntity<Object> handleBusiness(BusinessException ex, WebRequest request) {
		return respond(ex, ex.getErrorCode(), ex.getMessage(), request);
	}

	// SecurityConfig가 필터 단계에서 넘긴 예외도 아래 두 메서드로 온다.
	@ExceptionHandler(AuthenticationException.class)
	ResponseEntity<Object> handleAuthentication(AuthenticationException ex, WebRequest request) {
		return respond(ex, CommonErrorCode.UNAUTHENTICATED, null, request);
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
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		// Spring이 500으로 매핑하는 예외(응답 직렬화 실패 등)는 catch-all을 거치지 않으므로 여기서 한 번에 남긴다.
		if (statusCode.is5xxServerError()) {
			log.error("서버 오류 응답: status={} {}", statusCode.value(), request.getDescription(false), ex);
		}
		ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
		if (response != null && response.getBody() instanceof ProblemDetail problem && !hasCode(problem)) {
			CommonErrorCode errorCode = CommonErrorCode.fromStatus(statusCode);
			problem.setDetail(errorCode.message());
			problem.setProperty(CODE, errorCode.code());
		}
		return response;
	}

	private ResponseEntity<Object> respond(Exception ex, ErrorCode errorCode, String detail, WebRequest request) {
		ProblemDetail problem = problem(errorCode, detail != null ? detail : errorCode.message());
		return handleExceptionInternal(ex, problem, new HttpHeaders(), errorCode.status(), request);
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
