package com.jey.dongtanpass.application;

import java.util.List;

import com.jey.core.campus.api.CampusCatalog;
import com.jey.core.campus.api.CampusInfo;
import com.jey.core.campus.api.DiscountTypeInfo;
import com.jey.dongtanpass.domain.Benefit;
import com.jey.dongtanpass.domain.BenefitRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 패스 제도에 참여하는 JEY 지점(연계 학원 발급분을 받고, JEY 발급분을 발급한다)과 그 지점이 주는 할인. 지금은 한 지점, 한 할인이다.
 * 지점 이름과 할인율은 기초 모듈에서 읽는다(할인율을 바꾸면 이미 발급한 연계 학원 발급분에도 바로 보인다. JEY 발급분은 패스에 남은 값이다).
 */
@Service
@Transactional(readOnly = true)
public class PassBenefits {

	private final BenefitRepository benefits;

	private final CampusCatalog catalog;

	PassBenefits(BenefitRepository benefits, CampusCatalog catalog) {
		this.benefits = benefits;
		this.catalog = catalog;
	}

	/**
	 * 할인 유형이 꺼져 있어도 그대로 돌려준다(학부모 화면은 발급 때 안내한 혜택을 보여 준다).
	 * 할인을 새로 적용하는 자리(사용 처리)는 그 할인 유형이 켜져 있는지 따로 확인한다.
	 *
	 * @throws IllegalStateException 받는 지점이 한 곳이 아니거나, 그 지점이나 할인 유형이 없을 때(설정 오류)
	 */
	public PassBenefit current() {
		List<Benefit> all = benefits.findAll();
		// 받는 지점이 늘면 화면과 사용 처리를 함께 바꿔야 한다. 조용히 하나를 고르지 않는다.
		if (all.size() != 1) {
			throw new IllegalStateException("패스를 받는 지점이 한 곳이 아니다: " + all.size() + "곳");
		}
		Benefit benefit = all.getFirst();
		CampusInfo campus = catalog.findCampus(benefit.getProviderCampusId())
			.orElseThrow(() -> new IllegalStateException(
					"패스를 받는 지점이 없다: campusId=" + benefit.getProviderCampusId()));
		DiscountTypeInfo discount = catalog.findDiscountType(benefit.getDiscountTypeCode())
			.orElseThrow(() -> new IllegalStateException("패스 할인 유형이 없다: " + benefit.getDiscountTypeCode()));
		return new PassBenefit(campus.id(), campus.name(), discount.code(), discount.rate(), discount.active());
	}

}
