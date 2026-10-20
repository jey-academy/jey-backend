package com.jey.dongtanpass.api;

import java.util.Optional;

import com.jey.core.shared.DomainEvent;
import com.jey.core.shared.EntityRef;
import com.jey.core.shared.EventMetadata;

/**
 * 연계 학원이 JEY 발급 패스를 썼다. 봉투의 지점은 패스를 발급한 지점이고, 행위자는 사용 처리한 연계 학원 계정이다.
 * 개인정보(학생 이름, 학부모 번호)와 금액은 싣지 않는다(금액은 사용 기록에 있다).
 */
public record PartnerPassRedeemed(EventMetadata metadata, long passId, long partnerId, long studentId)
		implements DomainEvent {

	public static final String TYPE = "dongtanpass.partner-pass-redeemed";

	public static final int VERSION = 1;

	@Override
	public Optional<EntityRef> subject() {
		return Optional.of(PassRefs.pass(passId));
	}

}
