package com.jey.dongtanpass.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.jey.dongtanpass.application.CampusIssuedPass;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "JEY가 발급한 패스")
record CampusPassIssueResponse(
		@Schema(description = "패스 ID. 링크 새로 만들기와 취소에 쓴다", requiredMode = RequiredMode.REQUIRED) Long id,
		@Schema(description = "패스 번호(예: DT-K7M2QX). 연계 학원에서 QR이 안 될 때 쓴다", requiredMode = RequiredMode.REQUIRED) String passNo,
		@Schema(description = "학부모 링크의 토큰. 이 응답에만 있고 다시 볼 수 없다. 잃어버리면 링크를 새로 만든다",
				requiredMode = RequiredMode.REQUIRED) String linkToken,
		@Schema(description = "발급 시각", requiredMode = RequiredMode.REQUIRED) Instant issuedAt,
		@Schema(description = "이날(한국 날짜)까지 쓸 수 있다. 발급일에 한 달을 더한 날",
				requiredMode = RequiredMode.REQUIRED) LocalDate expiresOn,
		@Schema(description = "이 패스의 할인율(발급 때의 설정). 5%는 0.05. 설정을 바꿔도 이 패스는 그대로다",
				requiredMode = RequiredMode.REQUIRED) BigDecimal discountRate) {

	static CampusPassIssueResponse from(CampusIssuedPass pass) {
		return new CampusPassIssueResponse(pass.id(), pass.passNo(), pass.linkToken(), pass.issuedAt(),
				pass.expiresOn(), pass.discountRate());
	}

	// 링크 토큰 원문이 로그에 찍히지 않게 한다.
	@Override
	public String toString() {
		return "CampusPassIssueResponse[id=" + id + ", passNo=" + passNo + ", expiresOn=" + expiresOn + "]";
	}

}
