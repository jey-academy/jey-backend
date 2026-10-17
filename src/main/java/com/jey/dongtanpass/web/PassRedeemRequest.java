package com.jey.dongtanpass.web;

import com.jey.core.shared.RejectUnknownFields;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

// 모르는 칸이 있으면 400이다. 칸 이름을 잘못 적은 요청이 학생 없이 처리되려다 다른 오류로 보이지 않게.
@RejectUnknownFields
@Schema(description = "패스를 쓸 학생. 데스크가 방금 등록한 학생이다")
record PassRedeemRequest(
		@Schema(description = "학생 ID. 학생 등록(POST /api/v1/students)의 응답에서 받는다") @NotNull Long studentId) {

}
