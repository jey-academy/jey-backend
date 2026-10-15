package com.jey.dongtanpass.web;

import java.time.LocalDate;

import com.jey.core.shared.Money;
import com.jey.core.shared.PhoneNumber;
import com.jey.core.shared.RejectUnknownFields;
import com.jey.dongtanpass.application.ConsentNotice;
import com.jey.dongtanpass.application.NewPass;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PaymentMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 칸 이름의 오타(예: 동의 칸)가 빈 값으로 처리되지 않게 모르는 칸을 거부한다.
@RejectUnknownFields
@Schema(description = "패스 발급. 결제를 확인한 뒤 보낸다. 결제 기록은 발급한 뒤 고칠 수 없다")
record PassIssueRequest(
		@Schema(description = "학생 이름. 앞뒤 공백은 뗀다. 데스크에서 등록할 이름과 같아야 한다(띄어쓰기는 달라도 된다)") @NotBlank @Size(
				max = Pass.MAX_STUDENT_NAME_LENGTH) String studentName,
		@Schema(description = "학부모 휴대전화 번호. 확인용 값으로만 저장되고 다시 보여 주지 않는다") @NotNull PhoneNumber guardianPhone,
		@Schema(description = "결제일(YYYY-MM-DD). 오늘(한국 날짜)보다 뒤는 안 된다") @NotNull LocalDate paidOn,
		@Schema(description = "결제 금액(원). 0보다 큰 1원 단위의 정수") @NotNull Money paidAmount,
		@Schema(description = "결제 수단") @NotNull PaymentMethod paymentMethod,
		@Schema(description = "학부모에게 고지 문구를 보여 주고 동의를 받았는지. true여야 발급한다") @NotNull Boolean consentConfirmed,
		@Schema(description = "보여 준 고지 문구의 버전(고지 문구 API의 version). 지금 버전과 다르면 400") @NotBlank @Size(
				max = ConsentNotice.MAX_VERSION_LENGTH) String noticeVersion) {

	NewPass toNewPass() {
		return new NewPass(studentName, guardianPhone, paidOn, paidAmount, paymentMethod, consentConfirmed,
				noticeVersion);
	}

	// 이름과 번호가 로그에 찍히지 않게 한다.
	@Override
	public String toString() {
		return "PassIssueRequest[paidOn=" + paidOn + ", paymentMethod=" + paymentMethod + ", noticeVersion="
				+ noticeVersion + "]";
	}

}
