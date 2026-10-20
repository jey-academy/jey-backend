package com.jey.billing.api;

import java.time.Instant;
import java.util.Optional;

import com.jey.core.member.api.MemberRefs;
import com.jey.core.shared.DomainEvent;
import com.jey.core.shared.EntityRef;
import com.jey.core.shared.EventMetadata;
import com.jey.core.shared.Money;

/**
 * 데스크가 받은 결제를 기록했다. 봉투의 지점은 청구의 지점(청구를 만들 때 학생의 지점), 행위자는 기록한 직원이다. 대상은 학생이다.
 * 개인정보(이름, 전화번호)는 싣지 않는다. 지금은 감사 로그가 이 이벤트로 남고, 장부 기록을 붙이면 이 이벤트를 구독한다.
 *
 * @param targetMonth 청구의 대상 월(YYYY-MM)
 * @param amount 받은 수강료(청구 합계와 같다)
 * @param bookFee 받은 교재비(청구와 따로)
 */
public record PaymentCompleted(EventMetadata metadata, long paymentId, long invoiceId, long studentId,
		String targetMonth, PaymentMethod method, Money amount, Money bookFee, Instant paidAt) implements DomainEvent {

	public static final String TYPE = "billing.payment-completed";

	public static final int VERSION = 1;

	@Override
	public Optional<EntityRef> subject() {
		return Optional.of(MemberRefs.student(studentId));
	}

}
