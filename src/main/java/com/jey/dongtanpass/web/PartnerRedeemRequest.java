package com.jey.dongtanpass.web;

import java.util.List;

import com.jey.core.shared.Money;
import com.jey.core.shared.PhoneNumber;
import com.jey.dongtanpass.domain.StudentNameKey;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "연계 학원의 사용 처리. 이름과 번호는 본인 확인 때와 같은 것을 보낸다(서버가 다시 맞춰 본다)")
record PartnerRedeemRequest(
		@Schema(description = "학원이 등록 서류에 적은 학생 이름. 띄어쓰기는 달라도 된다", minLength = 1) @NotNull @StudentNameText String studentName,
		@Schema(description = "학부모 휴대전화 번호(전체) 1~" + HolderCheckRequest.MAX_PHONES
				+ "개. 하나라도 패스의 번호와 같으면 맞다") @NotNull @Size(min = 1,
						max = HolderCheckRequest.MAX_PHONES) List<@NotNull PhoneNumber> guardianPhones,
		@Schema(description = "원래 수강료(원, 첫 달 한 달 치). 0보다 크고 1원 단위, 10,000,000원까지. 아니면 400(DONGTANPASS_INVALID_ORIGINAL_FEE). 음수·문자열처럼 금액으로 읽을 수 없으면 400(COMMON_VALIDATION_FAILED)")
		@NotNull Money originalFee) {

	// 칸 검증(@StudentNameText)을 거친 뒤에 부른다.
	StudentNameKey studentNameKey() {
		return StudentNameKey.of(studentName);
	}

	// 이름과 번호가 로그에 찍히지 않게 한다.
	@Override
	public String toString() {
		return "PartnerRedeemRequest[studentName=***, guardianPhones="
				+ (guardianPhones == null ? null : guardianPhones.size()) + "개, originalFee=" + originalFee + "]";
	}

}
