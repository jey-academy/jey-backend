package com.jey.billing.application;

import java.time.Instant;

import com.jey.billing.api.PaymentMethod;
import com.jey.billing.domain.Payment;
import com.jey.billing.domain.PaymentStatus;
import com.jey.core.shared.Money;

/**
 * 결제 하나.
 *
 * @param studentName 학생 이름. 학생을 찾지 못하면 {@code null}(있어서는 안 되는 자료)
 * @param amount 받은 수강료
 * @param bookFee 받은 교재비
 * @param canceledAt 취소하지 않았으면 {@code null}
 */
public record PaymentView(long paymentId, long invoiceId, long campusId, long studentId, String studentName,
		PaymentMethod method, Money amount, Money bookFee, PaymentStatus status, Instant paidAt, Instant canceledAt) {

	static PaymentView of(Payment payment, String studentName) {
		return new PaymentView(payment.getId(), payment.getInvoiceId(), payment.getCampusId(), payment.getStudentId(),
				studentName, payment.getMethod(), payment.getAmount(), payment.getBookFee(), payment.getStatus(), payment.getPaidAt(),
				payment.getCanceledAt());
	}

}
