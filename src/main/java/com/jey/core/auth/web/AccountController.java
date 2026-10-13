package com.jey.core.auth.web;

import com.jey.core.auth.AccountService;
import com.jey.core.auth.api.AllowedRoles;
import com.jey.core.auth.api.UserRole;
import com.jey.core.auth.domain.UserStatus;
import com.jey.core.shared.NotIdempotent;
import com.jey.core.shared.PageLimits;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 계정 관리는 관리자만 한다 (ADR-0030). 자기 비밀번호를 바꾸는 것은 AuthController에 있다.
// 계정 API의 POST는 Idempotency-Key를 받지 않는다. 그 장치는 본문의 지문(SHA-256)을 저장소에 하루 동안 두는데,
// 비밀번호가 든 본문의 빠른 해시를 그렇게 두지 않는다. 비밀번호가 없는 차단과 풀기는 다시 보내도 결과가 같아 받지 않는다.
@RestController
@RequestMapping("/api/v1/accounts")
@AllowedRoles(UserRole.ADMIN)
class AccountController {

	static final int MAX_PAGE_SIZE = 100;

	private final AccountService accountService;

	AccountController(AccountService accountService) {
		this.accountService = accountService;
	}

	@GetMapping
	AccountPageResponse accounts(@RequestParam(required = false) UserRole role,
			@RequestParam(required = false) Long campusId, @RequestParam(required = false) UserStatus status,
			@Parameter(description = PageLimits.PAGE_DESCRIPTION) @RequestParam(defaultValue = "0") @Min(0) @Max(
					PageLimits.MAX_PAGE) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
		return AccountPageResponse.from(accountService.search(role, campusId, status, page, size));
	}

	@GetMapping("/{id}")
	AccountResponse account(@PathVariable long id) {
		return AccountResponse.from(accountService.get(id));
	}

	@NotIdempotent("비밀번호가 실린 본문의 지문을 저장하지 않는다. 다시 보내면 아이디 중복(409)으로 거부된다")
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	AccountResponse create(@Valid @RequestBody AccountCreateRequest body) {
		return AccountResponse.from(accountService.create(body.toNewAccount()));
	}

	@Operation(summary = "이름·역할·지점 고치기", description = "역할이나 지점을 바꾸면 그 계정은 로그아웃된다. 자기 자신의 역할·지점은 바꿀 수 없다")
	@PutMapping("/{id}")
	AccountResponse update(@PathVariable long id, @Valid @RequestBody AccountUpdateRequest body) {
		return AccountResponse.from(accountService.update(id, body.toChange()));
	}

	@Operation(summary = "차단", description = "로그인을 막고 그 계정을 로그아웃시킨다. 자기 자신은 차단할 수 없다")
	@NotIdempotent("다시 보내도 결과가 같다. 이미 차단된 계정의 세션을 한 번 더 끊을 뿐이다")
	@PostMapping("/{id}/disable")
	AccountResponse disable(@PathVariable long id) {
		return AccountResponse.from(accountService.disable(id));
	}

	@Operation(summary = "차단 풀기")
	@NotIdempotent("다시 보내도 결과가 같다")
	@PostMapping("/{id}/enable")
	AccountResponse enable(@PathVariable long id) {
		return AccountResponse.from(accountService.enable(id));
	}

	@Operation(summary = "비밀번호 다시 정하기",
			description = "그 계정은 로그아웃된다. 차단된 계정은 차단된 채로 비밀번호만 바뀐다. 자기 비밀번호는 /auth/password로 바꾼다")
	@NotIdempotent("비밀번호가 실린 본문의 지문을 저장하지 않는다. 다시 보내도 결과가 같다")
	@PostMapping("/{id}/password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void resetPassword(@PathVariable long id, @Valid @RequestBody PasswordResetRequest body) {
		accountService.resetPassword(id, body.password());
	}

}
