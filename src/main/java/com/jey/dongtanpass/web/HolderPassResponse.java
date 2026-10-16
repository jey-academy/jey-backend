package com.jey.dongtanpass.web;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.jey.dongtanpass.application.HolderPassView;
import com.jey.dongtanpass.domain.PassState;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "학부모 화면의 패스. 학생 이름은 가린 것이고 학부모 번호는 없다")
record HolderPassResponse(
		@Schema(description = "패스 번호. QR이 안 될 때 데스크에 불러 준다", requiredMode = RequiredMode.REQUIRED) String passNo,
		@Schema(description = "발급한 연계 학원", requiredMode = RequiredMode.REQUIRED) String partnerName,
		@Schema(description = "가린 학생 이름(첫 글자와 끝 글자만, 예: 김*윤)", requiredMode = RequiredMode.REQUIRED) String studentName,
		@Schema(description = "이날(한국 날짜)까지 쓸 수 있다", requiredMode = RequiredMode.REQUIRED) LocalDate expiresOn,
		@Schema(description = "오늘 기준으로 보이는 상태. ISSUED 쓸 수 있음, EXPIRED 쓰지 않은 채 기간이 지남, REDEEMED 사용됨, CANCELED 발급 취소",
				requiredMode = RequiredMode.REQUIRED) PassState state,
		@Schema(description = "할인율. 5%는 0.05", requiredMode = RequiredMode.REQUIRED) BigDecimal discountRate,
		@Schema(description = "패스를 받는 지점", requiredMode = RequiredMode.REQUIRED) String campusName,
		@Schema(description = "고지 문구의 버전", requiredMode = RequiredMode.REQUIRED) String noticeVersion,
		@Schema(description = "고지 문구. 여러 줄이다(줄바꿈 문자로 나뉜다)", requiredMode = RequiredMode.REQUIRED) String noticeText) {

	static HolderPassResponse from(HolderPassView view) {
		return new HolderPassResponse(view.passNo(), view.partnerName(), view.maskedStudentName(), view.expiresOn(),
				view.state(), view.discountRate(), view.campusName(), view.noticeVersion(), view.noticeText());
	}

}
