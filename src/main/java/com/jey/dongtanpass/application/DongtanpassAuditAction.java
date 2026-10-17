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
	PARTNER_CREATED("dongtanpass.partner-created", AuditOutcome.DONE),

	/**
	 * 학원을 껐다. 이미 꺼져 있던 학원을 다시 끈 것(남은 계정을 차단하는 복구)은 적지 않는다.
	 * 그 학원 계정의 차단은 계정마다 {@code account.disabled}로 따로 남는다.
	 */
	PARTNER_DEACTIVATED("dongtanpass.partner-deactivated", AuditOutcome.DONE),

	/** 학원을 켰다. 계정은 풀리지 않는다. 이미 켜져 있던 학원을 다시 켠 것은 적지 않는다. */
	PARTNER_ACTIVATED("dongtanpass.partner-activated", AuditOutcome.DONE),

	/** 학부모 링크를 새로 만들었다. 대상은 그 패스다({@code PassRefs.pass}). 토큰은 싣지 않는다. */
	PASS_LINK_RENEWED("dongtanpass.pass-link-renewed", AuditOutcome.DONE),

	/** 학부모가 학생 이름과 번호로 링크를 다시 받았다. 대상은 그 패스다. 행위자는 없다(로그인 없는 요청). 토큰은 싣지 않는다. */
	HOLDER_LINK_RENEWED("dongtanpass.holder-link-renewed", AuditOutcome.DONE),

	/**
	 * 학부모 링크 다시 받기에서 이름과 번호는 맞았지만 쓸 수 있는 패스가 없어 거부했다. 대상은 사정을 알린 패스이고,
	 * {@code reason}에 거부의 에러 코드를 싣는다. 그 이름과 번호의 패스가 있다는 사실이 드러난 응답이라 남긴다.
	 */
	HOLDER_LINK_DENIED("dongtanpass.holder-link-denied", AuditOutcome.DENIED),

	/** 학부모 링크 다시 받기가 틀린 시도 한도에 처음 막혔다(구간마다 한 번). 대상은 없다. */
	HOLDER_RELINK_LIMITED("dongtanpass.holder-relink-limited", AuditOutcome.DENIED),

	/** 데스크의 패스 번호 확인이 계정별 한도에 처음 막혔다(구간마다 한 번). 대상은 없다. 행위자가 그 계정이다. */
	PASS_NO_VERIFY_LIMITED("dongtanpass.pass-no-verify-limited", AuditOutcome.DENIED),

	/** 데스크의 본인 미리 확인이 계정별 한도에 처음 막혔다(구간마다 한 번). 대상은 없다. 행위자가 그 계정이다. */
	HOLDER_CHECK_LIMITED("dongtanpass.holder-check-limited", AuditOutcome.DENIED),

	/** 쓰지 않은 패스를 취소했다. 대상은 그 패스다. 결제 환불은 연계 학원이 따로 한다. */
	PASS_CANCELED("dongtanpass.pass-canceled", AuditOutcome.DONE);

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
