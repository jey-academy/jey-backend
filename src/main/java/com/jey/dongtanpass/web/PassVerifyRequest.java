package com.jey.dongtanpass.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 정상 QR 토큰은 약 90자다. 아주 긴 글자는 서명을 계산하기 전에 칸 오류로 거른다.
@Schema(description = "데스크가 확인할 패스")
record PassVerifyRequest(
		@Schema(description = "학부모 화면의 QR에서 읽은 토큰. 그대로 보낸다") @NotBlank @Size(max = 200) String qrToken) {

	// 토큰이 로그에 찍히지 않게 한다.
	@Override
	public String toString() {
		return "PassVerifyRequest[qrToken=***]";
	}

}
