package com.jey.dongtanpass.application;

import java.time.LocalDate;

import com.jey.core.shared.Money;
import com.jey.dongtanpass.domain.PaymentMethod;

/**
 * JEY 지점이 발급할 패스의 내용. 학생 이름과 학부모 번호는 학생 자료에서 가져오므로 받지 않는다.
 *
 * @param paidOn 근거 결제(그 달의 수강료 결제)의 결제일
 * @param consentConfirmed 관리자가 학부모에게 고지하고 동의를 받았는지
 * @param noticeVersion 보여 준 고지 문구(JEY 발급)의 버전
 */
public record NewCampusPass(long studentId, LocalDate paidOn, Money paidAmount, PaymentMethod paymentMethod,
		boolean consentConfirmed, String noticeVersion) {
}
