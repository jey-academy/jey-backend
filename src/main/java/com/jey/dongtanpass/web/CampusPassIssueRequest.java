package com.jey.dongtanpass.web;

import java.time.LocalDate;

import com.jey.core.shared.Money;
import com.jey.dongtanpass.application.ConsentNotice;
import com.jey.dongtanpass.application.NewCampusPass;
import com.jey.dongtanpass.domain.PaymentMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "JEY 발급. 학생의 그 달 수강료 결제를 확인한 뒤 보낸다. 학생 이름과 학부모 번호는 학생 자료에서 가져온다. 결제 기록은 발급한 뒤 고칠 수 없다")
record CampusPassIssueRequest(@Schema(description = "학생 ID. 패스를 발급하는 지점에 재원 중이어야 한다") @NotNull Long studentId,
		@Schema(description = "근거 결제의 결제일(YYYY-MM-DD). 오늘(한국 날짜)보다 뒤는 안 된다") @NotNull LocalDate paidOn,
		@Schema(description = "근거 결제의 금액(원). JEY가 이 학생에게 받은 그 달 수강료다. 0보다 큰 1원 단위의 정수. 기록으로만 남고 할인 계산에 쓰지 않는다(연계 학원이 사용 처리 때 원래 수강료를 따로 넣는다)") @NotNull Money paidAmount,
		@Schema(description = "근거 결제의 수단") @NotNull PaymentMethod paymentMethod,
		@Schema(description = "학부모에게 고지 문구를 보여 주고 동의를 받았는지. true여야 발급한다") @NotNull Boolean consentConfirmed,
		@Schema(description = "보여 준 고지 문구의 버전(JEY 발급 고지 문구 API의 version). 지금 버전과 다르면 400") @NotBlank @Size(
				max = ConsentNotice.MAX_VERSION_LENGTH) String noticeVersion) {

	NewCampusPass toNewCampusPass() {
		return new NewCampusPass(studentId, paidOn, paidAmount, paymentMethod, consentConfirmed, noticeVersion);
	}

}
