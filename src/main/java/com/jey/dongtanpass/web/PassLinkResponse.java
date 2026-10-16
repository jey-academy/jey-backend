package com.jey.dongtanpass.web;

import com.jey.dongtanpass.application.RenewedLink;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "새로 만든 학부모 링크")
record PassLinkResponse(@Schema(description = "패스 ID", requiredMode = RequiredMode.REQUIRED) Long id,
		@Schema(description = "새 링크의 토큰. 이 응답에만 있다. 예전 링크는 더 쓸 수 없다",
				requiredMode = RequiredMode.REQUIRED) String linkToken) {

	static PassLinkResponse from(RenewedLink link) {
		return new PassLinkResponse(link.id(), link.linkToken());
	}

	// 링크 토큰 원문이 로그에 찍히지 않게 한다.
	@Override
	public String toString() {
		return "PassLinkResponse[id=" + id + "]";
	}

}
