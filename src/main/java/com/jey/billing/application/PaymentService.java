package com.jey.billing.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.jey.billing.api.PaymentCanceled;
import com.jey.billing.api.PaymentCompleted;
import com.jey.billing.api.PaymentMethod;
import com.jey.billing.domain.Invoice;
import com.jey.billing.domain.InvoiceRepository;
import com.jey.billing.domain.InvoiceStateException;
import com.jey.billing.domain.Payment;
import com.jey.billing.domain.PaymentAlreadyCanceledException;
import com.jey.billing.domain.PaymentAmountMismatchException;
import com.jey.billing.domain.PaymentRepository;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.CampusScope;
import com.jey.core.auth.api.CurrentActor;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.EventMetadata;
import com.jey.core.shared.Money;
import com.jey.core.shared.UniqueKeys;

/**
 * 데스크가 받은 결제(포스기 카드, 현금)를 청구에 기록하고 취소한다. 데스크 직원과 관리자가 부른다(컨트롤러가 막는다).
 *
 * <p>결제와 결제 취소는 청구 행을 먼저 잠그고 상태를 본다. 같은 청구를 동시에 두 번 결제하면 뒤의 요청은 PAID를 보고 409다
 * (유효한 결제가 둘 생기지 않게 하는 마지막 방어선은 유니크 키). 결제 완료·취소는 이벤트로 낸다(감사 로그는 이벤트로 남는다).
 */
@Service
public class PaymentService {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private static final LocalDate MIN_DATE = LocalDate.of(2000, 1, 1);

	private static final LocalDate MAX_DATE = LocalDate.of(2099, 12, 31);

	private final InvoiceRepository invoices;

	private final PaymentRepository payments;

	private final TransactionTemplate transaction;

	private final ApplicationEventPublisher events;

	private final CurrentActor currentActor;

	private final Clock clock;

	PaymentService(InvoiceRepository invoices, PaymentRepository payments, TransactionTemplate transaction,
			ApplicationEventPublisher events, CurrentActor currentActor, Clock clock) {
		this.invoices = invoices;
		this.payments = payments;
		this.transaction = transaction;
		this.events = events;
		this.currentActor = currentActor;
		this.clock = clock;
	}

	/**
	 * 결제를 기록한다. 결제 시각은 지금이다(데스크가 받은 직후에 기록한다).
	 *
	 * @param amount 받은 수강료. 청구 합계와 같아야 한다
	 * @param bookFee 받은 교재비({@link Payment#MAX_BOOK_FEE}까지)
	 * @throws BusinessException 교재비가 규칙 밖, 없는 청구, 다룰 수 없는 지점, 결제 전이 아닌 청구, 수강료가 청구 합계와 다를 때
	 *     (확인 순서도 이대로다)
	 */
	public PaymentView record(long invoiceId, PaymentMethod method, Money amount, Money bookFee,
			AuthenticatedUser user) {
		if (!Payment.acceptsBookFee(bookFee)) {
			throw new BusinessException(BillingErrorCode.INVALID_BOOK_FEE);
		}
		try {
			return transaction.execute(status -> {
				Invoice invoice = lockedInvoice(invoiceId, user);
				Payment payment;
				try {
					payment = Payment.record(invoice, method, amount, bookFee, clock.instant());
				}
				catch (InvoiceStateException e) {
					throw new BusinessException(BillingErrorCode.INVOICE_NOT_PAYABLE);
				}
				catch (PaymentAmountMismatchException e) {
					throw new BusinessException(BillingErrorCode.PAYMENT_AMOUNT_MISMATCH);
				}
				Payment saved = payments.saveAndFlush(payment);
				events.publishEvent(new PaymentCompleted(
						EventMetadata.create(PaymentCompleted.TYPE, PaymentCompleted.VERSION, invoice.getCampusId(),
								currentActor.get(), clock),
						saved.getId(), invoice.getId(), invoice.getStudentId(), invoice.getTargetMonth().toString(),
						saved.getMethod(), saved.getAmount(), saved.getBookFee(), saved.getPaidAt()));
				return PaymentView.of(saved);
			});
		}
		catch (DataIntegrityViolationException e) {
			if (UniqueKeys.isViolationOf(e, Payment.OPEN_UNIQUE_KEY)) {
				throw new BusinessException(BillingErrorCode.PAYMENT_ALREADY_EXISTS);
			}
			throw e;
		}
	}

	/**
	 * 결제를 취소한다(환불은 데스크에서 따로 한다). 청구는 다시 결제 전이다.
	 *
	 * @throws BusinessException 없는 결제, 다룰 수 없는 지점, 이미 취소한 결제
	 */
	public PaymentView cancel(long paymentId, AuthenticatedUser user) {
		return transaction.execute(status -> {
			// 청구를 잠근 뒤 결제도 잠가서 읽는다(잠그지 않는 읽기는 잠금 전의 스냅숏을 본다 — PaymentRepository#findByIdForUpdate).
			long invoiceId = payments.findInvoiceIdById(paymentId)
				.orElseThrow(() -> new BusinessException(BillingErrorCode.PAYMENT_NOT_FOUND));
			Invoice invoice = lockedInvoice(invoiceId, user);
			Payment payment = payments.findByIdForUpdate(paymentId)
				.orElseThrow(() -> new IllegalStateException("잠근 사이 결제가 없어졌다: paymentId=" + paymentId));
			try {
				payment.cancel(invoice, clock.instant());
			}
			catch (PaymentAlreadyCanceledException e) {
				throw new BusinessException(BillingErrorCode.PAYMENT_ALREADY_CANCELED);
			}
			catch (InvoiceStateException e) {
				// 유효한 결제인데 청구가 PAID가 아니다. 상태가 어긋난 자료라 500으로 둔다(원인을 찾을 수 있게 ID를 싣는다).
				throw new IllegalStateException("결제와 청구의 상태가 어긋났다: paymentId=" + paymentId + ", invoiceId="
						+ invoiceId + ", invoiceStatus=" + e.current(), e);
			}
			events.publishEvent(new PaymentCanceled(EventMetadata.create(PaymentCanceled.TYPE, PaymentCanceled.VERSION,
					invoice.getCampusId(), currentActor.get(), clock), payment.getId(), invoice.getId(),
					invoice.getStudentId()));
			return PaymentView.of(payment);
		});
	}

	/**
	 * 결제 목록. 결제일(한국 날짜)로 자르고 양끝을 포함한다. 최신 결제 순.
	 *
	 * @throws BusinessException 시작일이 종료일보다 뒤이거나 2000-01-01~2099-12-31 밖이거나 1년을 넘을 때
	 */
	@Transactional(readOnly = true)
	public Page<PaymentView> list(CampusScope scope, LocalDate from, LocalDate to, int page, int size) {
		if (from.isAfter(to) || from.isBefore(MIN_DATE) || to.isAfter(MAX_DATE)) {
			throw new BusinessException(BillingErrorCode.INVALID_PERIOD);
		}
		if (to.isAfter(from.plusYears(1))) {
			throw new BusinessException(BillingErrorCode.PERIOD_TOO_LONG);
		}
		var start = from.atStartOfDay(SEOUL).toInstant();
		var end = to.plusDays(1).atStartOfDay(SEOUL).toInstant();
		PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Order.desc("paidAt"), Sort.Order.desc("id")));
		Page<Payment> found = switch (scope) {
			case CampusScope.All all -> payments.findByPaidAtGreaterThanEqualAndPaidAtLessThan(start, end, pageable);
			case CampusScope.Only only -> payments.findByCampusIdAndPaidAtGreaterThanEqualAndPaidAtLessThan(
					only.campusId(), start, end, pageable);
		};
		return found.map(PaymentView::of);
	}

	private Invoice lockedInvoice(long invoiceId, AuthenticatedUser user) {
		Invoice invoice = invoices.findByIdForUpdate(invoiceId)
			.orElseThrow(() -> new BusinessException(BillingErrorCode.INVOICE_NOT_FOUND));
		user.requireCampus(invoice.getCampusId());
		return invoice;
	}

}
