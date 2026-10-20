package com.jey.billing.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import com.jey.billing.api.PaymentMethod;
import com.jey.core.shared.BaseEntity;
import com.jey.core.shared.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 데스크가 받은 결제의 기록. {@link #record}로만 만든다. 청구 하나에 유효한 결제(COMPLETED)는 하나다
 * (유니크 키 {@value #OPEN_UNIQUE_KEY} — 유효한 결제만 {@code open_key = 1}).
 * 결제와 청구의 상태는 함께 바뀐다: 결제하면 청구가 PAID, 결제를 취소하면 청구가 다시 ISSUED.
 */
@Entity
@Table(name = "billing_payment")
public class Payment extends BaseEntity {

	public static final String OPEN_UNIQUE_KEY = "uk_billing_payment_open";

	/** 교재비 상한. 입력 실수를 막는 값이다. 바꾸면 결제 요청의 설명(PaymentRequest)도 함께 고친다. */
	public static final Money MAX_BOOK_FEE = Money.of(10_000_000);

	/** 교재비의 규칙: 0원 이상, 1원 단위, {@link #MAX_BOOK_FEE} 이하. 규칙은 여기 한 곳이다(서비스는 이것으로 먼저 보고 업무 오류로 답한다). */
	public static boolean acceptsBookFee(Money bookFee) {
		return bookFee != null && bookFee.isWholeWon() && bookFee.compareTo(MAX_BOOK_FEE) <= 0;
	}

	private static final byte OPEN = 1;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "invoice_id", nullable = false, updatable = false)
	private Long invoiceId;

	@Column(name = "campus_id", nullable = false, updatable = false)
	private Long campusId;

	@Column(name = "student_id", nullable = false, updatable = false)
	private Long studentId;

	@Enumerated(EnumType.STRING)
	@Column(name = "method", nullable = false, updatable = false, length = 20)
	private PaymentMethod method;

	@Column(name = "amount", nullable = false, updatable = false)
	private Money amount;

	@Column(name = "book_fee", nullable = false, updatable = false)
	private Money bookFee;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private PaymentStatus status;

	@Column(name = "open_key")
	private Byte openKey;

	@Column(name = "paid_at", nullable = false, updatable = false)
	private Instant paidAt;

	@Column(name = "canceled_at")
	private Instant canceledAt;

	protected Payment() {
	}

	private Payment(Invoice invoice, PaymentMethod method, Money amount, Money bookFee, Instant paidAt) {
		this.invoiceId = invoice.getId();
		this.campusId = invoice.getCampusId();
		this.studentId = invoice.getStudentId();
		this.method = method;
		this.amount = amount;
		this.bookFee = bookFee;
		this.status = PaymentStatus.COMPLETED;
		this.openKey = OPEN;
		// DB는 마이크로초까지 저장한다. 만든 직후의 응답·이벤트와 나중에 읽은 값이 같도록 미리 자른다.
		this.paidAt = paidAt.truncatedTo(ChronoUnit.MICROS);
	}

	/**
	 * 결제를 기록하고 청구를 결제됨으로 바꾼다.
	 *
	 * @param amount 받은 수강료. 청구 합계와 같아야 한다
	 * @param bookFee 받은 교재비. 0원 이상 1원 단위, {@link #MAX_BOOK_FEE}까지
	 * @throws PaymentAmountMismatchException 수강료가 청구 합계와 다를 때(청구는 그대로)
	 * @throws InvoiceStateException 결제 전 청구가 아닐 때
	 * @throws IllegalArgumentException 값이 없거나 교재비가 규칙 밖일 때
	 */
	public static Payment record(Invoice invoice, PaymentMethod method, Money amount, Money bookFee, Instant paidAt) {
		if (invoice == null || method == null || amount == null || bookFee == null || paidAt == null) {
			throw new IllegalArgumentException("invoice, method, amount, bookFee, paidAt은 비울 수 없다");
		}
		if (!acceptsBookFee(bookFee)) {
			throw new IllegalArgumentException("교재비는 1원 단위이고 " + MAX_BOOK_FEE.amount().toPlainString() + "원까지다");
		}
		if (invoice.getStatus() != InvoiceStatus.ISSUED) {
			throw new InvoiceStateException(invoice.getStatus());
		}
		if (amount.compareTo(invoice.getTotalAmount()) != 0) {
			throw new PaymentAmountMismatchException();
		}
		invoice.markPaid();
		return new Payment(invoice, method, amount, bookFee, paidAt);
	}

	/**
	 * 결제를 취소하고 청구를 다시 결제 전으로 돌린다(환불은 데스크에서 따로 한다).
	 *
	 * @param invoice 이 결제의 청구
	 * @throws PaymentAlreadyCanceledException 이미 취소한 결제일 때
	 * @throws IllegalArgumentException 다른 청구를 넘겼을 때
	 */
	public void cancel(Invoice invoice, Instant at) {
		if (at == null) {
			throw new IllegalArgumentException("at은 비울 수 없다");
		}
		if (invoice == null || !Objects.equals(invoice.getId(), invoiceId)) {
			throw new IllegalArgumentException("이 결제의 청구가 아니다");
		}
		if (status == PaymentStatus.CANCELED) {
			throw new PaymentAlreadyCanceledException();
		}
		invoice.reopen();
		this.status = PaymentStatus.CANCELED;
		this.openKey = null;
		this.canceledAt = at.truncatedTo(ChronoUnit.MICROS);
	}

	public Long getId() {
		return id;
	}

	public long getInvoiceId() {
		return invoiceId;
	}

	public long getCampusId() {
		return campusId;
	}

	public long getStudentId() {
		return studentId;
	}

	public PaymentMethod getMethod() {
		return method;
	}

	public Money getAmount() {
		return amount;
	}

	public Money getBookFee() {
		return bookFee;
	}

	public PaymentStatus getStatus() {
		return status;
	}

	public Instant getPaidAt() {
		return paidAt;
	}

	public Instant getCanceledAt() {
		return canceledAt;
	}

}
