package com.jey.dongtanpass.domain;

import java.math.BigDecimal;
import java.time.Instant;

import com.jey.core.shared.BaseEntity;
import com.jey.core.shared.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 연계 학원이 JEY 발급 패스를 쓴 기록. {@link Pass#redeemAtPartner}로만 만든다. 고치지 않는다.
 * 금액은 쓴 순간의 계산({@link PartnerDiscount})을 그대로 남긴다. 연계 학원의 결제 자체는 이 시스템이 다루지 않는다.
 */
@Entity
@Table(name = "dongtanpass_partner_redemption")
public class PartnerRedemption extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "pass_id", nullable = false, updatable = false)
	private Long passId;

	@Column(name = "partner_id", nullable = false, updatable = false)
	private Long partnerId;

	@Column(name = "student_id", nullable = false, updatable = false)
	private Long studentId;

	@Column(name = "original_fee", nullable = false, updatable = false)
	private Money originalFee;

	@Column(name = "discount_rate", nullable = false, updatable = false, precision = 5,
			scale = Pass.DISCOUNT_RATE_SCALE)
	private BigDecimal discountRate;

	@Column(name = "discount_amount", nullable = false, updatable = false)
	private Money discountAmount;

	@Column(name = "payable_amount", nullable = false, updatable = false)
	private Money payableAmount;

	@Column(name = "redeemed_at", nullable = false, updatable = false)
	private Instant redeemedAt;

	protected PartnerRedemption() {
	}

	private PartnerRedemption(long passId, long partnerId, long studentId, PartnerDiscount discount,
			Instant redeemedAt) {
		this.passId = passId;
		this.partnerId = partnerId;
		this.studentId = studentId;
		this.originalFee = discount.originalFee();
		this.discountRate = discount.discountRate();
		this.discountAmount = discount.discountAmount();
		this.payableAmount = discount.payableAmount();
		this.redeemedAt = redeemedAt;
	}

	/**
	 * @param partnerId 쓴 연계 학원
	 * @throws IllegalArgumentException 금액이나 사용 시각이 없을 때
	 */
	static PartnerRedemption of(long passId, long partnerId, long studentId, PartnerDiscount discount,
			Instant redeemedAt) {
		if (discount == null || redeemedAt == null) {
			throw new IllegalArgumentException("discount와 redeemedAt은 비울 수 없다");
		}
		return new PartnerRedemption(passId, partnerId, studentId, discount, redeemedAt);
	}

	public Long getId() {
		return id;
	}

	public Long getPassId() {
		return passId;
	}

	public Long getPartnerId() {
		return partnerId;
	}

	public Long getStudentId() {
		return studentId;
	}

	public Money getOriginalFee() {
		return originalFee;
	}

	public BigDecimal getDiscountRate() {
		return discountRate;
	}

	public Money getDiscountAmount() {
		return discountAmount;
	}

	public Money getPayableAmount() {
		return payableAmount;
	}

	public Instant getRedeemedAt() {
		return redeemedAt;
	}

}
