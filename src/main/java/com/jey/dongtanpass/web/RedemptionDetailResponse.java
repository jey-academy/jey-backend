package com.jey.dongtanpass.web;

import java.time.Instant;
import java.time.LocalDate;

import com.jey.dongtanpass.application.RedemptionDetail;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "패스 사용 기록 하나. 청구 화면은 주소에 사용 기록만 두고 학생과 지점을 여기서 읽는다. 학부모 번호는 없다")
record RedemptionDetailResponse(
		@Schema(description = "사용 기록 ID", requiredMode = RequiredMode.REQUIRED) long redemptionId,
		@Schema(description = "사용 처리한 시각", requiredMode = RequiredMode.REQUIRED) Instant redeemedAt,
		@Schema(description = "사용일(한국 날짜)", requiredMode = RequiredMode.REQUIRED) LocalDate redeemedOn,
		@Schema(description = "사용한 지점(누가 볼 수 있는지의 기준)", requiredMode = RequiredMode.REQUIRED) long campusId,
		@Schema(description = "패스 ID", requiredMode = RequiredMode.REQUIRED) long passId,
		@Schema(description = "패스 번호", requiredMode = RequiredMode.REQUIRED) String passNo,
		@Schema(description = "패스를 발급한 연계 학원", requiredMode = RequiredMode.REQUIRED) String partnerName,
		@Schema(description = "학생 ID", requiredMode = RequiredMode.REQUIRED) long studentId,
		@Schema(description = "학생 이름. 학생을 찾지 못하면 null(있어서는 안 되는 자료)", requiredMode = RequiredMode.REQUIRED,
				nullable = true) String studentName,
		@Schema(description = "학생의 지금 지점. 반 목록과 청구는 이 지점 기준이다(사용 처리 뒤 지점을 옮겼으면 campusId와 다르다). "
				+ "학생을 찾지 못하면 null", requiredMode = RequiredMode.REQUIRED, nullable = true) Long studentCampusId,
		@Schema(description = "이 사용 기록에서 만든 열린 청구의 ID(결제 전이거나 결제한 청구). 만들지 않았거나 취소했으면 null",
				requiredMode = RequiredMode.REQUIRED, nullable = true) Long invoiceId) {

	static RedemptionDetailResponse from(RedemptionDetail detail) {
		return new RedemptionDetailResponse(detail.redemptionId(), detail.redeemedAt(), detail.redeemedOn(),
				detail.campusId(), detail.passId(), detail.passNo(), detail.partnerName(), detail.studentId(),
				detail.studentName(), detail.studentCampusId(), detail.invoiceId());
	}

}
