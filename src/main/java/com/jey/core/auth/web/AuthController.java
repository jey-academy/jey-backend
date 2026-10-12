package com.jey.core.auth.web;

import com.jey.core.auth.AccountService;
import com.jey.core.auth.LoginService;
import com.jey.core.auth.api.AnyRole;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.PublicEndpoint;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.CommonErrorCode;
import com.jey.core.shared.NotIdempotent;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

	private final LoginService loginService;

	private final AccountService accountService;

	AuthController(LoginService loginService, AccountService accountService) {
		this.loginService = loginService;
		this.accountService = accountService;
	}

	// CSRF 토큰은 누군가 읽을 때 만들어진다. 여기서 한 번 읽어 XSRF-TOKEN 쿠키가 응답에 실리게 한다(ADR-0019).
	@PublicEndpoint
	@GetMapping("/csrf")
	ResponseEntity<Void> csrf(CsrfToken csrfToken) {
		csrfToken.getToken();
		return ResponseEntity.noContent().build();
	}

	@PublicEndpoint
	@NotIdempotent("로그인하기 전이라 계정별로 키를 볼 수 없다. 다시 보내면 다시 로그인할 뿐이다")
	@PostMapping("/login")
	MeResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
			HttpServletResponse response) {
		return MeResponse.from(loginService.login(body.loginId(), body.password(), request, response));
	}

	// 로그인 정보가 예상한 타입이 아니면 null로 넘어오지 않고 예외가 난다.
	@AnyRole
	@GetMapping("/me")
	MeResponse me(@AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedUser user) {
		return MeResponse.from(user);
	}

	@AnyRole
	@NotIdempotent("다시 보내도 결과가 같다. 이미 끝난 세션을 한 번 더 끝낼 뿐이다")
	@PostMapping("/logout")
	ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) {
		loginService.logout(request, response, authentication);
		return ResponseEntity.noContent().build();
	}

	@Operation(summary = "자기 비밀번호 바꾸기",
			description = "지금 화면은 로그인된 채로 남고, 같은 계정으로 로그인된 다른 화면은 로그아웃된다. "
					+ "지금 비밀번호를 여러 번 틀리면 429와 Retry-After(초)가 온다")
	@AnyRole
	@NotIdempotent("비밀번호가 실린 본문의 지문을 저장하지 않는다. 다시 보내면 지금 비밀번호가 맞지 않아 거부된다")
	@PostMapping("/password")
	ResponseEntity<Void> changePassword(@Valid @RequestBody PasswordChangeRequest body, HttpServletRequest request) {
		// 세션을 새로 만들지 않는다. 로그인한 요청에는 세션이 이미 있다.
		HttpSession session = request.getSession(false);
		if (session == null) {
			throw new BusinessException(CommonErrorCode.UNAUTHENTICATED);
		}
		accountService.changeOwnPassword(body.currentPassword(), body.newPassword(), session.getId());
		// 남긴 세션의 ID도 바꾼다. 이 세션의 쿠키를 누가 베껴 갖고 있었다면 그 사본은 여기서 못 쓰게 된다.
		// 바꾼 사람의 브라우저는 이 응답으로 새 쿠키를 받는다.
		request.changeSessionId();
		return ResponseEntity.noContent().build();
	}

}
