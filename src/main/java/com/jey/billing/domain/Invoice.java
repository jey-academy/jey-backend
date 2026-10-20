package com.jey.billing.domain;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.hibernate.annotations.BatchSize;

import com.jey.core.shared.BaseEntity;
import com.jey.core.shared.Money;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/**
 * 수강료 청구. 학생 한 명의 한 달 수강료다. {@link #issue}로만 만든다.
 * 한 학생 한 달에 열린 청구는 하나다(유니크 키 {@value #OPEN_UNIQUE_KEY} — 열린 청구만 {@code open_key = 1}).
 */
@Entity
@Table(name = "billing_invoice")
public class Invoice extends BaseEntity {

	public static final String OPEN_UNIQUE_KEY = "uk_billing_invoice_open";

	public static final int MAX_LINES = 4;

	private static final byte OPEN = 1;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "campus_id", nullable = false, updatable = false)
	private Long campusId;

	@Column(name = "student_id", nullable = false, updatable = false)
	private Long studentId;

	@Convert(converter = YearMonthConverter.class)
	@Column(name = "target_month", nullable = false, updatable = false, length = 7)
	private YearMonth targetMonth;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private InvoiceStatus status;

	@Column(name = "open_key")
	private Byte openKey;

	@Enumerated(EnumType.STRING)
	@Column(name = "discount_policy", nullable = false, updatable = false, length = 20)
	private DiscountPolicy discountPolicy;

	@Column(name = "total_amount", nullable = false, updatable = false)
	private Money totalAmount;

	@OneToMany(cascade = CascadeType.ALL)
	@JoinColumn(name = "invoice_id", nullable = false, updatable = false)
	@OrderBy("sortOrder")
	@BatchSize(size = 100)
	private List<InvoiceLine> lines = new ArrayList<>();

	protected Invoice() {
	}

	private Invoice(long campusId, long studentId, YearMonth targetMonth, DiscountPolicy policy,
			List<InvoiceLine> lines) {
		this.campusId = campusId;
		this.studentId = studentId;
		this.targetMonth = targetMonth;
		this.discountPolicy = policy;
		this.status = InvoiceStatus.ISSUED;
		this.openKey = OPEN;
		Money total = Money.ZERO;
		for (InvoiceLine line : lines) {
			line.placeAt(this.lines.size());
			this.lines.add(line);
			total = total.plus(line.getChargeAmount());
		}
		// 라인마다 끊은 금액을 더하기만 한다(합계에서 다시 끊지 않는다).
		this.totalAmount = total;
	}

	/**
	 * 열린 청구를 만든다.
	 *
	 * @param policy 라인 계산에 쓴 할인 방식(남겨 둔다)
	 * @throws IllegalArgumentException 라인이 없거나 {@value #MAX_LINES}개를 넘거나, 같은 반이 두 번 있거나, 값이 없을 때
	 */
	public static Invoice issue(long campusId, long studentId, YearMonth targetMonth, DiscountPolicy policy,
			List<InvoiceLine> lines) {
		if (targetMonth == null || policy == null || lines == null) {
			throw new IllegalArgumentException("targetMonth, policy, lines는 비울 수 없다");
		}
		if (lines.isEmpty() || lines.size() > MAX_LINES) {
			throw new IllegalArgumentException("청구 라인은 1~" + MAX_LINES + "개다");
		}
		if (new HashSet<>(lines.stream().map(InvoiceLine::getClassId).toList()).size() != lines.size()) {
			throw new IllegalArgumentException("같은 반을 두 라인에 넣을 수 없다");
		}
		return new Invoice(campusId, studentId, targetMonth, policy, lines);
	}

	public Long getId() {
		return id;
	}

	public long getCampusId() {
		return campusId;
	}

	public long getStudentId() {
		return studentId;
	}

	public YearMonth getTargetMonth() {
		return targetMonth;
	}

	public InvoiceStatus getStatus() {
		return status;
	}

	public DiscountPolicy getDiscountPolicy() {
		return discountPolicy;
	}

	public Money getTotalAmount() {
		return totalAmount;
	}

	public List<InvoiceLine> getLines() {
		return List.copyOf(lines);
	}

}
