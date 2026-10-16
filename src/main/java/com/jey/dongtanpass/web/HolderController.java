package com.jey.dongtanpass.web;

import com.jey.core.auth.api.PublicEndpoint;
import com.jey.core.shared.NotIdempotent;
import com.jey.dongtanpass.application.HolderPassService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 학부모는 로그인하지 않는다. 링크 토큰이 열쇠다(본문으로 받는다). 보안 설정의 허용 경로에도 적혀 있다.
// 로그인이 없어 멱등 키를 계정별로 볼 수 없다. CSRF 토큰은 필요하다.
@RestController
@RequestMapping("/api/v1/dongtanpass/holder")
class HolderController {

	private final HolderPassService holderPassService;

	HolderController(HolderPassService holderPassService) {
		this.holderPassService = holderPassService;
	}

	@Operation(summary = "학부모 패스 열기",
			description = "링크 토큰으로 패스를 연다. 로그인 없이 부르고 CSRF 토큰은 싣는다. 없거나 바뀐 링크는 404(DONGTANPASS_PASS_NOT_FOUND) 하나로 답한다")
	@PublicEndpoint
	@NotIdempotent("로그인하지 않은 경로라 키를 계정별로 볼 수 없다. 바꾸는 자료가 없다")
	@PostMapping("/view")
	HolderPassResponse view(@Valid @RequestBody HolderTokenRequest body) {
		return HolderPassResponse.from(holderPassService.view(body.token()));
	}

}
