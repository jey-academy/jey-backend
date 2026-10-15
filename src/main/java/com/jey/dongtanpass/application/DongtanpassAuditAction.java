package com.jey.dongtanpass.application;

import com.jey.core.audit.api.AuditAction;
import com.jey.core.audit.api.AuditOutcome;

/**
 * 동탄고 패스 모듈에서 감사 로그에 남기는 행위. 이름과 "한 일인지 거부된 시도인지"를 이 한 곳에서 정한다.
 * 이름은 이미 쌓인 기록에 적혀 있고 조회 조건으로 쓰이므로 바꾸지 않는다.
 *
 * <p>연계 학원 계정을 만든 일은 여기에 없다. 계정을 만드는 쪽이 {@code account.created}로 적는다(연계 학원 식별자와 함께).
 */
enum DongtanpassAuditAction implements AuditAction {

	/** 대상은 그 연계 학원이다({@code PartnerRefs.partner}). */
	PARTNER_CREATED("dongtanpass.partner-created", AuditOutcome.DONE);

	private final String code;

	private final AuditOutcome outcome;

	DongtanpassAuditAction(String code, AuditOutcome outcome) {
		this.code = code;
		this.outcome = outcome;
	}

	@Override
	public String code() {
		return code;
	}

	@Override
	public AuditOutcome outcome() {
		return outcome;
	}

}
