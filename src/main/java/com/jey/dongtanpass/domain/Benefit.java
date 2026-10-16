package com.jey.dongtanpass.domain;

import com.jey.core.shared.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 패스를 받는 지점과 그 지점이 주는 할인. 마이그레이션이 넣고 화면에서 고치지 않는다.
 * 지점 이름과 할인율은 저장하지 않는다(기초 모듈에서 읽는다).
 */
@Entity
@Table(name = "dongtanpass_benefit")
public class Benefit extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "provider_campus_id", nullable = false, updatable = false)
	private Long providerCampusId;

	@Column(name = "discount_type_code", nullable = false, updatable = false, length = 30)
	private String discountTypeCode;

	protected Benefit() {
	}

	public Long getId() {
		return id;
	}

	public Long getProviderCampusId() {
		return providerCampusId;
	}

	public String getDiscountTypeCode() {
		return discountTypeCode;
	}

}
