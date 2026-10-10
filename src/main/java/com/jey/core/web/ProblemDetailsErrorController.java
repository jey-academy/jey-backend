package com.jey.core.web;

import java.net.URI;

import com.jey.core.shared.CommonErrorCode;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서블릿 컨테이너의 오류 경로. 필터가 {@code sendError}로 끝낸 요청(방화벽이 거부한 주소 등)이 여기로 온다.
 * Spring Boot의 기본 오류 응답 대신 다른 오류와 같은 Problem Details로 답한다.
 *
 * <p>이 타입의 빈이 있으면 Spring Boot의 기본 오류 컨트롤러는 등록되지 않는다.
 */
@RestController
class ProblemDetailsErrorController implements ErrorController {

	@RequestMapping("${server.error.path:/error}")
	ResponseEntity<ProblemDetail> error(HttpServletRequest request) {
		// 컨테이너가 넘긴 것이 아니라 누군가 이 주소를 직접 부른 경우에는 오류 상태가 없다. 없는 주소로 답한다.
		HttpStatusCode status = (request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) instanceof Integer code)
				? HttpStatusCode.valueOf(code) : HttpStatus.NOT_FOUND;
		// 원래 사유 문구에는 내부 정보가 섞일 수 있어 상태에 맞는 공통 문구만 내보낸다.
		CommonErrorCode errorCode = CommonErrorCode.fromStatus(status);
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, errorCode.message());
		problem.setProperty("code", errorCode.code());
		URI instance = originalUri(request);
		if (instance != null) {
			problem.setInstance(instance);
		}
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
	}

	// 거부된 주소는 URI로 읽을 수 없는 모양일 수 있다. 그때는 싣지 않는다.
	private static URI originalUri(HttpServletRequest request) {
		if (request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI) instanceof String uri) {
			try {
				return URI.create(uri);
			}
			catch (IllegalArgumentException ex) {
				return null;
			}
		}
		return null;
	}

}
