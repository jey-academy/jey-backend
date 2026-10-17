package com.jey.dongtanpass.web;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.jey.dongtanpass.application.VerifiedPassView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "데스크가 확인한 패스. 확인을 통과했으므로 쓸 수 있는 패스다. 학생 이름은 가린 것이고 학부모 번호는 없다")
record VerifiedPassResponse(
		@Schema(description = "패스 ID. 사용 처리(본인 확인, 사용)에 쓴다", requiredMode = RequiredMode.REQUIRED) long passId,
		@Schema(description = "패스 번호", requiredMode = RequiredMode.REQUIRED) String passNo,
		@Schema(description = "발급한 연계 학원", requiredMode = RequiredMode.REQUIRED) String partnerName,
		@Schema(description = "가린 학생 이름(첫 글자와 끝 글자만, 예: 김*윤). 직원이 등록 폼에 적은 이름은 사용 처리에서 맞춰 본다",
				requiredMode = RequiredMode.REQUIRED) String studentName,
		@Schema(description = "발급일(한국 날짜)", requiredMode = RequiredMode.REQUIRED) LocalDate issuedOn,
		@Schema(description = "이날(한국 날짜)까지 쓸 수 있다", requiredMode = RequiredMode.REQUIRED) LocalDate expiresOn,
		@Schema(description = "할인율. 5%는 0.05", requiredMode = RequiredMode.REQUIRED) BigDecimal discountRate) {

	static VerifiedPassResponse from(VerifiedPassView view) {
		return new VerifiedPassResponse(view.passId(), view.passNo(), view.partnerName(), view.maskedStudentName(),
				view.issuedOn(), view.expiresOn(), view.discountRate());
	}

}
