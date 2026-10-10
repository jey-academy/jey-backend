package com.jey.core.auth.web;

import com.jey.core.auth.LoginService;
import com.jey.core.auth.api.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

	AuthController(LoginService loginService) {
		this.loginService = loginService;
	}

	// CSRF 토큰은 누군가 읽을 때 만들어진다. 여기서 한 번 읽어 XSRF-TOKEN 쿠키가 응답에 실리게 한다.
	// 프론트는 앱을 시작할 때 이 주소를 한 번 호출한다.
	@GetMapping("/csrf")
	ResponseEntity<Void> csrf(CsrfToken csrfToken) {
		csrfToken.getToken();
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/login")
	MeResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
			HttpServletResponse response) {
		return MeResponse.from(loginService.login(body.loginId(), body.password(), request, response));
	}

	@GetMapping("/me")
	MeResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
		return MeResponse.from(user);
	}

	@PostMapping("/logout")
	ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) {
		loginService.logout(request, response, authentication);
		return ResponseEntity.noContent().build();
	}

}
