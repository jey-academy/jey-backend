package com.jey.billing.application;

import java.time.Instant;

import com.jey.billing.api.PaymentMethod;
import com.jey.billing.domain.Payment;
import com.jey.billing.domain.PaymentStatus;
import com.jey.core.shared.Money;

/**
 * 결제 하나.
 *
 * @param amount 받은 수강료
 * @param bookFee 받은 교재비
 * @param canceledAt 취소하지 않았으면 {@code null}
 */
public record PaymentView(long paymentId, long invoiceId, long campusId, long studentId, PaymentMethod method,
		Money amount, Money bookFee, PaymentStatus status, Instant paidAt, Instant canceledAt) {

	static PaymentView of(Payment payment) {
		return new PaymentView(payment.getId(), payment.getInvoiceId(), payment.getCampusId(), payment.getStudentId(),
				payment.getMethod(), payment.getAmount(), payment.getBookFee(), payment.getStatus(), payment.getPaidAt(),
				payment.getCanceledAt());
	}

}
