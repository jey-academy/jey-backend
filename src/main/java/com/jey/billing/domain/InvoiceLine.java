package com.jey.billing.domain;

import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.BatchSize;

import com.jey.billing.api.InvoiceRules;
import com.jey.core.shared.BaseEntity;
import com.jey.core.shared.Money;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/**
 * 청구 라인. 반 하나의 그 달 수강료. {@link #of}로 만들고 {@link Invoice#issue}에 넘긴다. 고치지 않는다.
 * 8회 기준 수강료는 만든 순간의 값을 복사해 둔다(수강료표가 바뀌어도 따라 바뀌지 않는다).
 */
@Entity
@Table(name = "billing_invoice_line")
public class InvoiceLine extends BaseEntity {

	public static final int MAX_DISCOUNTS = InvoiceRules.MAX_DISCOUNTS;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "class_id", nullable = false, updatable = false)
	private Long classId;

	@Column(name = "lesson_count", nullable = false, updatable = false)
	private int lessonCount;

	@Column(name = "base_fee_8", nullable = false, updatable = false)
	private Money baseFee8;

	@Column(name = "list_amount", nullable = false, updatable = false)
	private Money listAmount;

	@Column(name = "charge_amount", nullable = false, updatable = false)
	private Money chargeAmount;

	@Column(name = "sort_order", nullable = false, updatable = false)
	private int sortOrder;

	@OneToMany(cascade = CascadeType.ALL)
	@JoinColumn(name = "line_id", nullable = false, updatable = false)
	@OrderBy("sortOrder")
	@BatchSize(size = 100)
	private List<InvoiceLineDiscount> discounts = new ArrayList<>();

	protected InvoiceLine() {
	}

	private InvoiceLine(long classId, int lessonCount, Money baseFee8, LineCharge charge) {
		this.classId = classId;
		this.lessonCount = lessonCount;
		this.baseFee8 = baseFee8;
		this.listAmount = charge.listAmount();
		this.chargeAmount = charge.chargeAmount();
		for (AppliedDiscount applied : charge.discounts()) {
			discounts.add(new InvoiceLineDiscount(applied, discounts.size()));
		}
	}

	/**
	 * @param charge {@link TuitionCalculator#charge}로 이 반·횟수·기준 수강료를 계산한 결과
	 * @throws IllegalArgumentException 계산 결과가 없거나 할인이 {@value #MAX_DISCOUNTS}개를 넘을 때
	 */
	public static InvoiceLine of(long classId, int lessonCount, Money baseFee8, LineCharge charge) {
		if (baseFee8 == null || charge == null) {
			throw new IllegalArgumentException("baseFee8와 charge는 비울 수 없다");
		}
		if (charge.discounts().size() > MAX_DISCOUNTS) {
			throw new IllegalArgumentException("할인은 라인마다 " + MAX_DISCOUNTS + "개까지다");
		}
		return new InvoiceLine(classId, lessonCount, baseFee8, charge);
	}

	void placeAt(int sortOrder) {
		this.sortOrder = sortOrder;
	}

	public Long getId() {
		return id;
	}

	public long getClassId() {
		return classId;
	}

	public int getLessonCount() {
		return lessonCount;
	}

	public Money getBaseFee8() {
		return baseFee8;
	}

	public Money getListAmount() {
		return listAmount;
	}

	public Money getChargeAmount() {
		return chargeAmount;
	}

	public List<InvoiceLineDiscount> getDiscounts() {
		return List.copyOf(discounts);
	}

}
