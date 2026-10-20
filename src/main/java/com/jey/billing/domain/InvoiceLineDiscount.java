package com.jey.billing.domain;

import java.math.BigDecimal;

import com.jey.core.shared.BaseEntity;
import com.jey.core.shared.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 청구 라인에 적용한 할인 하나. {@link InvoiceLine}이 만든다. 고치지 않는다. */
@Entity
@Table(name = "billing_invoice_line_discount")
public class InvoiceLineDiscount extends BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "discount_type_code", nullable = false, updatable = false, length = 30)
	private String discountTypeCode;

	@Column(name = "rate", nullable = false, updatable = false, precision = 5, scale = 4)
	private BigDecimal rate;

	@Column(name = "amount", nullable = false, updatable = false)
	private Money amount;

	// 할인을 넘긴 모듈의 참조값(예: 패스 번호). 해석하지 않고 저장만 한다.
	@Column(name = "discount_ref", updatable = false, length = 100)
	private String discountRef;

	@Column(name = "sort_order", nullable = false, updatable = false)
	private int sortOrder;

	protected InvoiceLineDiscount() {
	}

	InvoiceLineDiscount(AppliedDiscount applied, int sortOrder) {
		this.discountTypeCode = applied.code();
		this.rate = applied.rate();
		this.amount = applied.amount();
		this.discountRef = applied.ref();
		this.sortOrder = sortOrder;
	}

	public Long getId() {
		return id;
	}

	public String getDiscountTypeCode() {
		return discountTypeCode;
	}

	public BigDecimal getRate() {
		return rate;
	}

	public Money getAmount() {
		return amount;
	}

	public String getDiscountRef() {
		return discountRef;
	}

}
