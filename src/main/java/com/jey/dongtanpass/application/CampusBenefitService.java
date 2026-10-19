package com.jey.dongtanpass.application;

import java.math.BigDecimal;
import java.util.List;

import com.jey.core.audit.api.AuditEntry;
import com.jey.core.audit.api.AuditLog;
import com.jey.dongtanpass.domain.CampusBenefit;
import com.jey.dongtanpass.domain.CampusBenefitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 반대 방향(JEY 발급 → 연계 학원 사용) 패스의 할인율을 읽고 바꾼다. 관리자만 쓴다(컨트롤러가 막는다).
 * 바꾸면 감사 로그에 새 값을 남긴다(이름·번호가 없는 설정이다).
 */
@Service
public class CampusBenefitService {

	private final CampusBenefitRepository benefits;

	private final AuditLog auditLog;

	CampusBenefitService(CampusBenefitRepository benefits, AuditLog auditLog) {
		this.benefits = benefits;
		this.auditLog = auditLog;
	}

	/**
	 * @throws IllegalStateException 설정이 한 줄이 아닐 때(마이그레이션이 넣은 줄이 없거나 늘었다 — 설정 오류)
	 */
	@Transactional(readOnly = true)
	public BigDecimal currentRate() {
		return only().getDiscountRate();
	}

	/**
	 * @return 바꾼 할인율
	 * @throws IllegalArgumentException 할인율이 비었거나 규칙 밖일 때
	 */
	@Transactional
	public BigDecimal change(BigDecimal rate) {
		CampusBenefit benefit = only();
		benefit.changeRate(rate);
		auditLog.record(AuditEntry.of(DongtanpassAuditAction.CAMPUS_BENEFIT_CHANGED)
			.with("discountRate", benefit.getDiscountRate().toPlainString()));
		return benefit.getDiscountRate();
	}

	// 할인율이 둘이면 어느 쪽을 패스에 남길지 정할 수 없다. 조용히 하나를 고르지 않는다.
	private CampusBenefit only() {
		List<CampusBenefit> all = benefits.findAll();
		if (all.size() != 1) {
			throw new IllegalStateException("반대 방향 패스의 할인율 설정이 한 줄이 아니다: " + all.size() + "줄");
		}
		return all.getFirst();
	}

}
