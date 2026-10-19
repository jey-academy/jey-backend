package com.jey.dongtanpass.domain;

import java.math.BigDecimal;

import com.jey.core.shared.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 반대 방향(JEY 발급 → 연계 학원 사용) 패스의 할인율. 마이그레이션이 한 줄을 넣고 관리자가 값만 바꾼다.
 * 발급할 때 이 값을 패스에 남긴다(바꿔도 이미 발급한 패스는 그대로).
 */
@Entity
@Table(name = "dongtanpass_campus_benefit")
public class CampusBenefit extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "discount_rate", nullable = false, precision = 5, scale = Pass.DISCOUNT_RATE_SCALE)
	private BigDecimal discountRate;

	protected CampusBenefit() {
	}

	/**
	 * @throws IllegalArgumentException 할인율이 비었거나 규칙 밖일 때({@link Pass#validRate})
	 */
	public void changeRate(BigDecimal rate) {
		this.discountRate = Pass.validRate(rate);
	}

	public Long getId() {
		return id;
	}

	public BigDecimal getDiscountRate() {
		return discountRate;
	}

}
