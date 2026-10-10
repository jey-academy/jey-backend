package com.jey.core.web;

import com.jey.core.shared.BusinessException;
import com.jey.core.shared.CommonErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 아직 실제 컨트롤러가 없어서 예외 변환을 검증하려고 둔 테스트 전용 컨트롤러.
@RestController
@RequestMapping("/api/v1/test-errors")
class ErrorTestController {

	@GetMapping("/business")
	void business() {
		throw new BusinessException(CommonErrorCode.CONFLICT, "이미 사용한 패스입니다.");
	}

	@GetMapping("/unexpected")
	void unexpected() {
		throw new IllegalStateException("jdbc:mysql://secret-host:3306/jey");
	}

	@PostMapping("/validated")
	void validated(@Valid @RequestBody Body body) {
	}

	@GetMapping("/param")
	void param(@RequestParam @Min(1) int size) {
	}

	record Body(@NotBlank String name, @Min(1) int count) {
	}

}
