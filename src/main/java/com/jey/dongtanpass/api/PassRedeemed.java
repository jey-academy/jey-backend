package com.jey.dongtanpass.api;

import java.util.Optional;

import com.jey.core.shared.DomainEvent;
import com.jey.core.shared.EntityRef;
import com.jey.core.shared.EventMetadata;

/**
 * 데스크가 패스를 사용 처리했다(ADR-0033). 봉투의 지점은 쓴 지점(패스를 받는 지점)이고, 행위자는 처리한 직원이다.
 * 개인정보(학생 이름, 학부모 번호)는 싣지 않는다. 금액과 청구는 다루지 않는다.
 */
public record PassRedeemed(EventMetadata metadata, long passId, long studentId, long partnerId)
		implements DomainEvent {

	public static final String TYPE = "dongtanpass.pass-redeemed";

	public static final int VERSION = 1;

	@Override
	public Optional<EntityRef> subject() {
		return Optional.of(PassRefs.pass(passId));
	}

}
