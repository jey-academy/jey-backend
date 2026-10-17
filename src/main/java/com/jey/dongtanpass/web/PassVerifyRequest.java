package com.jey.dongtanpass.web;

import com.jey.dongtanpass.domain.PassNumber;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// QR 토큰과 패스 번호 중 정확히 하나를 받는다. 둘 다이거나 둘 다 없으면 본문의 모양이 틀린 것이다(400 COMMON_BAD_REQUEST).
// 정상 QR 토큰은 약 90자다. 아주 긴 글자는 서명을 계산하기 전에 칸 오류로 거른다.
// 패스 번호는 형식 그대로만 받는다(대문자, 앞뒤 공백 없음). 틀리면 오타이므로 그 칸의 400으로 알린다.
@Schema(description = "데스크가 확인할 패스. qrToken과 passNo 중 정확히 하나를 보낸다")
record PassVerifyRequest(
		@Schema(description = "학부모 화면의 QR에서 읽은 토큰. 그대로 보낸다") @Size(max = 200) String qrToken,
		@Schema(description = "QR이 안 될 때 학부모가 불러 주는 패스 번호(예: DT-ABC234). 대문자로 보낸다")
		@Pattern(regexp = PassNumber.REGEX) String passNo) {

	PassVerifyRequest {
		if ((qrToken == null) == (passNo == null)) {
			throw new IllegalArgumentException("qrToken과 passNo 중 정확히 하나를 보내야 한다");
		}
	}

	// 토큰이 로그에 찍히지 않게 한다.
	@Override
	public String toString() {
		return "PassVerifyRequest[qrToken=" + (qrToken == null ? null : "***") + ", passNo=" + passNo + "]";
	}

}
