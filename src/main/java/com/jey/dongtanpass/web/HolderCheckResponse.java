package com.jey.dongtanpass.web;

import com.jey.dongtanpass.application.HolderCheckResult;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "본인 미리 확인의 결과. 이름과 학부모 번호를 따로 답한다. 최종 판단은 사용 처리가 다시 한다")
record HolderCheckResponse(
		@Schema(description = "학생 이름이 패스와 같은지(띄어쓰기와 무관)", requiredMode = RequiredMode.REQUIRED) boolean nameMatches,
		@Schema(description = "보낸 번호 중 하나가 패스를 받을 때 적은 학부모 번호와 같은지", requiredMode = RequiredMode.REQUIRED) boolean phoneMatches) {

	static HolderCheckResponse from(HolderCheckResult result) {
		return new HolderCheckResponse(result.nameMatches(), result.phoneMatches());
	}

}
