package com.jey.core.web;

import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

// 컨테이너가 오류 경로로 넘길 때 요청에 붙이는 속성을 직접 넣어서 확인한다. 실제로 넘어오는 경우는 FilterStageErrorTest가 본다.
@ExtendWith(OutputCaptureExtension.class)
class ProblemDetailsErrorControllerTest {

	private final ProblemDetailsErrorController controller = new ProblemDetailsErrorController();

	private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");

	@Test
	void 상태는_그대로_두고_공통_코드와_문구를_붙인다() {
		request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 400);
		request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/auth//me");

		ResponseEntity<ProblemDetail> response = controller.error(request);

		assertThat(response.getStatusCode().value()).isEqualTo(400);
		assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
		ProblemDetail problem = response.getBody();
		assertThat(problem.getStatus()).isEqualTo(400);
		assertThat(problem.getDetail()).isEqualTo("요청 형식이 올바르지 않습니다.");
		assertThat(problem.getProperties()).containsEntry("code", "COMMON_BAD_REQUEST");
		assertThat(problem.getInstance()).hasToString("/api/v1/auth//me");
	}

	// 공통 코드에 없는 상태도 상태 자체는 바꾸지 않는다.
	@Test
	void 서버_오류는_원래_사유_문구를_응답에_싣지_않고_로그에_남긴다(CapturedOutput output) {
		request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 503);
		request.setAttribute(RequestDispatcher.ERROR_MESSAGE, "jdbc:mysql://secret-host:3306/jey");
		request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/billing/invoices");
		request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("cause-marker"));

		ResponseEntity<ProblemDetail> response = controller.error(request);

		assertThat(response.getStatusCode().value()).isEqualTo(503);
		ProblemDetail problem = response.getBody();
		assertThat(problem.getProperties()).containsEntry("code", "COMMON_INTERNAL_ERROR");
		assertThat(problem.getDetail()).doesNotContain("secret-host");
		assertThat(problem.toString()).doesNotContain("secret-host").doesNotContain("cause-marker");
		assertThat(output).contains("오류 경로로 온 서버 오류: status=503 uri=/api/v1/billing/invoices")
				.contains("secret-host").contains("cause-marker");
	}

	@Test
	void 클라이언트_오류는_로그에_남기지_않는다(CapturedOutput output) {
		request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 400);

		controller.error(request);

		assertThat(output).doesNotContain("오류 경로로 온 서버 오류");
	}

	// 컨테이너가 넘긴 것이 아니라 누군가 이 주소를 직접 부른 경우다.
	@Test
	void 오류_상태가_없으면_없는_주소로_답한다() {
		ResponseEntity<ProblemDetail> response = controller.error(request);

		assertThat(response.getStatusCode().value()).isEqualTo(404);
		assertThat(response.getBody().getProperties()).containsEntry("code", "COMMON_NOT_FOUND");
		assertThat(response.getBody().getInstance()).isNull();
	}

	// 방화벽이 거부한 주소는 URI로 읽을 수 없는 모양일 수 있다.
	@Test
	void 원래_주소를_URI로_읽을_수_없으면_싣지_않는다() {
		request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 400);
		request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/a b");

		ResponseEntity<ProblemDetail> response = controller.error(request);

		assertThat(response.getStatusCode().value()).isEqualTo(400);
		assertThat(response.getBody().getInstance()).isNull();
	}

}
