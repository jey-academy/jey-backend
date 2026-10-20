package com.jey.dongtanpass.web;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.jey.dongtanpass.application.PartnerVerifiedPassView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

record PartnerVerifiedPassResponse(
		@Schema(description = "패스 ID. 본인 확인, 금액 미리 보기, 사용 처리에 쓴다", requiredMode = RequiredMode.REQUIRED) long passId,
		@Schema(description = "패스 번호", requiredMode = RequiredMode.REQUIRED) String passNo,
		@Schema(description = "패스를 발급한 JEY 지점", requiredMode = RequiredMode.REQUIRED) String campusName,
		@Schema(description = "가린 학생 이름(첫 글자와 끝 글자만, 예: 김*윤). 학원이 적은 이름은 본인 확인과 사용 처리에서 맞춰 본다",
				requiredMode = RequiredMode.REQUIRED) String studentName,
		@Schema(description = "발급일(한국 날짜)", requiredMode = RequiredMode.REQUIRED) LocalDate issuedOn,
		@Schema(description = "이날(한국 날짜)까지 쓸 수 있다", requiredMode = RequiredMode.REQUIRED) LocalDate expiresOn,
		@Schema(description = "이 패스의 할인율(발급 때 값). 5%는 0.05", requiredMode = RequiredMode.REQUIRED) BigDecimal discountRate) {

	static PartnerVerifiedPassResponse from(PartnerVerifiedPassView view) {
		return new PartnerVerifiedPassResponse(view.passId(), view.passNo(), view.campusName(),
				view.maskedStudentName(), view.issuedOn(), view.expiresOn(), view.discountRate());
	}

}
