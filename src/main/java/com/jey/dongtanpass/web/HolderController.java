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
	HolderPassResponse viewHolderPass(@Valid @RequestBody HolderTokenRequest body) {
		return HolderPassResponse.from(holderPassService.view(body.token()));
	}

	@Operation(summary = "학부모 QR 받기",
			description = "데스크에 보여 줄 QR 토큰을 준다. 60초 동안 쓸 수 있고 부를 때마다 새 토큰이다. 화면은 expiresAt 전에 다시 받는다. "
					+ "쓸 수 있는 패스만 받는다(기간이 지났거나 취소됐거나 사용됐으면 409)")
	@PublicEndpoint
	@NotIdempotent("로그인하지 않은 경로라 키를 계정별로 볼 수 없다. 부를 때마다 새 QR 토큰을 준다")
	@PostMapping("/qr")
	HolderQrResponse issueHolderQr(@Valid @RequestBody HolderTokenRequest body) {
		return HolderQrResponse.from(holderPassService.qr(body.token()));
	}

}
