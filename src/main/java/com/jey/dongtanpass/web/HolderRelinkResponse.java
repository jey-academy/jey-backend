package com.jey.dongtanpass.web;

import com.jey.dongtanpass.application.RelinkedLink;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "다시 받은 학부모 링크")
record HolderRelinkResponse(
		@Schema(description = "새 링크의 토큰(학부모 링크 주소 /p/{토큰}에 넣는다). 이 응답에만 있다. 예전 링크는 더 쓸 수 없다",
				requiredMode = RequiredMode.REQUIRED) String linkToken) {

	static HolderRelinkResponse from(RelinkedLink link) {
		return new HolderRelinkResponse(link.linkToken());
	}

	// 링크 토큰 원문이 로그에 찍히지 않게 한다.
	@Override
	public String toString() {
		return "HolderRelinkResponse[linkToken=***]";
	}

}
