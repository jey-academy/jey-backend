package com.jey.core.web;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.jey.core.auth.api.AnyRole;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.CommonErrorCode;
import com.jey.core.shared.Idempotent;
import com.jey.core.shared.NotIdempotent;
import com.jey.core.shared.RateLimitedException;
import com.jey.core.web.ErrorTestController.TestLimitCode;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 멱등 키 필터만 검증하기 위한 테스트 전용 컨트롤러. 실행될 때마다 횟수를 센다.
// 테스트 소스라 운영 빌드에는 포함되지 않는다. API 명세에도 싣지 않는다(ApiSpecTest).
@Hidden
@AnyRole
@RestController
@RequestMapping("/api/v1/test-idempotency")
class IdempotencyTestController {

	static final AtomicInteger EXECUTIONS = new AtomicInteger();

	// 느린 요청을 흉내 낸다. 테스트가 열어 줄 때까지 컨트롤러 안에서 기다린다.
	static volatile CountDownLatch entered = new CountDownLatch(0);

	static volatile CountDownLatch proceed = new CountDownLatch(0);

	@Idempotent
	@PostMapping("/things")
	@ResponseStatus(HttpStatus.CREATED)
	Map<String, Object> create(@Valid @RequestBody Thing body, @RequestParam(required = false) String note) {
		return Map.of("serial", EXECUTIONS.incrementAndGet(), "name", body.name());
	}

	@Idempotent
	@PostMapping("/empty")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void empty() {
		EXECUTIONS.incrementAndGet();
	}

	@Idempotent
	@PostMapping("/rejected")
	void rejected() {
		EXECUTIONS.incrementAndGet();
		throw new BusinessException(CommonErrorCode.CONFLICT, "이미 사용한 패스입니다.");
	}

	@Idempotent
	@PostMapping("/broken")
	void broken() {
		EXECUTIONS.incrementAndGet();
		throw new IllegalStateException("DB에 닿지 못했다");
	}

	@Idempotent
	@PostMapping("/limited")
	void limited() {
		EXECUTIONS.incrementAndGet();
		throw new RateLimitedException(TestLimitCode.TEST_TOO_MANY, Duration.ofMinutes(5));
	}

	@Idempotent
	@PostMapping("/slow")
	Map<String, Object> slow() throws InterruptedException {
		int serial = EXECUTIONS.incrementAndGet();
		entered.countDown();
		if (!proceed.await(10, TimeUnit.SECONDS)) {
			throw new IllegalStateException("테스트가 열어 주지 않았다");
		}
		return Map.of("serial", serial);
	}

	@NotIdempotent("필터가 표시 없는 경로를 건드리지 않는지 보는 테스트용이다")
	@PostMapping("/unguarded")
	Map<String, Object> unguarded() {
		return Map.of("serial", EXECUTIONS.incrementAndGet());
	}

	record Thing(@NotBlank String name) {
	}

}
