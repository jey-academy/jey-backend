package com.jey.billing.api;

import java.util.Optional;

import com.jey.core.member.api.MemberRefs;
import com.jey.core.shared.DomainEvent;
import com.jey.core.shared.EntityRef;
import com.jey.core.shared.EventMetadata;

/**
 * 데스크가 기록한 결제를 취소했다(환불은 데스크에서 따로 한다). 청구는 다시 결제 전이다. 대상은 학생이다.
 */
public record PaymentCanceled(EventMetadata metadata, long paymentId, long invoiceId, long studentId)
		implements DomainEvent {

	public static final String TYPE = "billing.payment-canceled";

	public static final int VERSION = 1;

	@Override
	public Optional<EntityRef> subject() {
		return Optional.of(MemberRefs.student(studentId));
	}

}
