package com.jey.dongtanpass.web;

import com.jey.core.shared.RejectUnknownFields;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

// 링크 토큰은 그 패스를 여는 열쇠라 주소로 받지 않는다(실패한 요청의 주소가 브라우저 콘솔과 로그에 남는다).
// 모양은 서비스가 본다. 모양이 틀린 토큰도 없는 토큰과 같은 404로 답하려고 여기서는 길이·형식을 보지 않는다.
@RejectUnknownFields
@Schema(description = "학부모 링크의 토큰")
record HolderTokenRequest(@Schema(description = "학부모 링크 주소의 토큰(/p/{토큰}). 그대로 보낸다") @NotBlank String token) {

	// 토큰이 로그에 찍히지 않게 한다.
	@Override
	public String toString() {
		return "HolderTokenRequest[token=***]";
	}

}
