package com.jey.dongtanpass.application;

import java.time.LocalDate;

import com.jey.core.shared.Money;
import com.jey.core.shared.PhoneNumber;
import com.jey.dongtanpass.domain.PaymentMethod;

/**
 * 발급할 패스의 내용.
 *
 * @param consentConfirmed 연계 학원이 학부모에게 고지하고 동의를 받았는지
 * @param noticeVersion 보여 준 고지 문구의 버전
 */
public record NewPass(String studentName, PhoneNumber guardianPhone, LocalDate paidOn, Money paidAmount,
		PaymentMethod paymentMethod, boolean consentConfirmed, String noticeVersion) {

	// 이름과 번호가 로그에 찍히지 않게 한다.
	@Override
	public String toString() {
		return "NewPass[paidOn=" + paidOn + ", paymentMethod=" + paymentMethod + ", noticeVersion=" + noticeVersion
				+ "]";
	}

}
