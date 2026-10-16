package com.jey.dongtanpass.web;

import java.time.Instant;

import com.jey.dongtanpass.application.QrCode;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "데스크에 보여 줄 QR")
record HolderQrResponse(
		@Schema(description = "QR에 그대로 넣을 글자. 서버만 만들 수 있고 데스크가 확인하면 다시 쓸 수 없다",
				requiredMode = RequiredMode.REQUIRED) String qrToken,
		@Schema(description = "이 시각부터 쓸 수 없다. 화면은 그 전에 다시 받는다", requiredMode = RequiredMode.REQUIRED) Instant expiresAt) {

	static HolderQrResponse from(QrCode code) {
		return new HolderQrResponse(code.token(), code.expiresAt());
	}

	// QR 토큰이 로그에 찍히지 않게 한다.
	@Override
	public String toString() {
		return "HolderQrResponse[expiresAt=" + expiresAt + "]";
	}

}
