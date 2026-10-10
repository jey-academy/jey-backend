package com.jey.core.web;

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

	@ExceptionHandler(BusinessException.class)
	ResponseEntity<Object> handleBusiness(BusinessException ex, WebRequest request) {
		return respond(ex, ex.getErrorCode(), ex.getMessage(), request);
	}

	// SecurityConfig의 EntryPoint·AccessDeniedHandler가 넘긴 예외도 여기로 온다.
	@ExceptionHandler(AuthenticationException.class)
	ResponseEntity<Object> handleAuthentication(AuthenticationException ex, WebRequest request) {
		return respond(ex, CommonErrorCode.UNAUTHENTICATED, CommonErrorCode.UNAUTHENTICATED.message(), request);
	}

	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, WebRequest request) {
		return respond(ex, CommonErrorCode.FORBIDDEN, CommonErrorCode.FORBIDDEN.message(), request);
	}

	// 원인은 로그에만 남기고 응답에는 일반 문구만 내보낸다.
	@ExceptionHandler(Exception.class)
	ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
		log.error("처리되지 않은 예외", ex);
		return respond(ex, CommonErrorCode.INTERNAL_ERROR, CommonErrorCode.INTERNAL_ERROR.message(), request);
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<FieldViolation> errors = ex.getBindingResult().getFieldErrors().stream()
				.map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
				.toList();
		return handleExceptionInternal(ex, validationProblem(errors), headers, status, request);
	}

	@Override
	protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<FieldViolation> errors = ex.getParameterValidationResults().stream()
				.flatMap(result -> result.getResolvableErrors().stream()
						.map(error -> new FieldViolation(result.getMethodParameter().getParameterName(),
								error.getDefaultMessage())))
				.toList();
		return handleExceptionInternal(ex, validationProblem(errors), headers, status, request);
	}

	// Spring MVC 표준 예외(404, 405, 본문 파싱 실패 등)는 code가 없다. HTTP 상태로 공통 코드를 붙이고,
	// Spring 기본 영어 문구 대신 공통 코드의 문구를 쓴다.
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
		if (response != null && response.getBody() instanceof ProblemDetail problem && !hasCode(problem)) {
			CommonErrorCode errorCode = CommonErrorCode.fromStatus(statusCode);
			problem.setDetail(errorCode.message());
			problem.setProperty(CODE, errorCode.code());
		}
		return response;
	}

	private ResponseEntity<Object> respond(Exception ex, ErrorCode errorCode, String detail, WebRequest request) {
		return handleExceptionInternal(ex, problem(errorCode, detail), new HttpHeaders(), errorCode.status(), request);
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

	record FieldViolation(String field, String message) {
	}

}
