package com.jey.billing.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jey.billing.domain.DiscountRate;
import com.jey.billing.domain.Invoice;
import com.jey.billing.domain.InvoiceLine;
import com.jey.billing.domain.InvoiceRepository;
import com.jey.billing.domain.LedgerSyncReason;
import com.jey.billing.domain.Payment;
import com.jey.billing.domain.PaymentRepository;
import com.jey.billing.domain.TuitionCalculator;
import com.jey.billing.domain.ledger.LedgerEntry;
import com.jey.core.campus.api.CampusCatalog;
import com.jey.core.campus.api.ClassInfo;
import com.jey.core.campus.api.DiscountTypeInfo;
import com.jey.core.member.api.ContactInfo;
import com.jey.core.member.api.MemberDirectory;
import com.jey.core.member.api.StudentInfo;

/**
 * 결제 하나를 장부의 한 줄 값({@link LedgerEntry})으로 모은다: 결제, 청구(라인 하나), 학생, 대표 연락처, 반 이름, 할인 이름.
 * 장부에 쓸 수 없는 결제는 이유를 돌려준다(반 나눔, 연락처 없음). 읽기만 하는 트랜잭션에서 읽는다.
 */
@Component
class LedgerEntryAssembler {

	/** 장부의 "매월 납입금액"은 8회 기준이다. */
	private static final int MONTHLY_BASE_LESSONS = 8;

	private final PaymentRepository payments;

	private final InvoiceRepository invoices;

	private final MemberDirectory members;

	private final CampusCatalog catalog;

	private final TransactionTemplate readOnly;

	private final Clock clock;

	LedgerEntryAssembler(PaymentRepository payments, InvoiceRepository invoices, MemberDirectory members,
			CampusCatalog catalog, PlatformTransactionManager transactionManager, Clock clock) {
		this.payments = payments;
		this.invoices = invoices;
		this.members = members;
		this.catalog = catalog;
		this.readOnly = new TransactionTemplate(transactionManager);
		this.readOnly.setReadOnly(true);
		this.clock = clock;
	}

	sealed interface Assembled {

		/** @param className 청구한 반의 이름(장부의 반 블록 이름) */
		record Ready(LedgerEntry entry, String className) implements Assembled {
		}

		record Rejected(LedgerSyncReason reason) implements Assembled {
		}

	}

	Assembled assemble(long paymentId) {
		return readOnly.execute(status -> {
			Payment payment = payments.findById(paymentId)
				.orElseThrow(() -> new IllegalStateException("결제가 없다: " + paymentId));
			Invoice invoice = invoices.findById(payment.getInvoiceId())
				.orElseThrow(() -> new IllegalStateException("청구가 없다: " + payment.getInvoiceId()));
			if (invoice.getLines().size() != 1) {
				return new Assembled.Rejected(LedgerSyncReason.SPLIT_CLASS);
			}
			InvoiceLine line = invoice.getLines().getFirst();
			StudentInfo student = members.findStudent(payment.getStudentId())
				.orElseThrow(() -> new IllegalStateException("학생이 없다: " + payment.getStudentId()));
			ContactInfo contact = members.primaryContact(payment.getStudentId()).orElse(null);
			if (contact == null) {
				return new Assembled.Rejected(LedgerSyncReason.NO_CONTACT);
			}
			String className = catalog.findClass(line.getClassId())
				.map(ClassInfo::name)
				.orElseThrow(() -> new IllegalStateException("반이 없다: " + line.getClassId()));
			List<String> discountNames = line.getDiscounts()
				.stream()
				.map(discount -> catalog.findDiscountType(discount.getDiscountTypeCode())
					.map(DiscountTypeInfo::name)
					.orElse(discount.getDiscountTypeCode()))
				.toList();
			List<DiscountRate> rates = line.getDiscounts()
				.stream()
				.map(discount -> new DiscountRate(discount.getDiscountTypeCode(), discount.getRate()))
				.toList();
			var monthlyBase = TuitionCalculator
				.charge(line.getBaseFee8(), MONTHLY_BASE_LESSONS, rates, invoice.getDiscountPolicy())
				.chargeAmount();
			LedgerEntry entry = new LedgerEntry(paymentId, student.schoolLabel(), student.name(), className,
					contact.phone(), invoice.getTargetMonth(), line.getLessonCount(), discountNames,
					invoice.getTotalAmount(), monthlyBase, LocalDate.ofInstant(payment.getPaidAt(), clock.getZone()),
					payment.getAmount(), payment.getBookFee());
			return new Assembled.Ready(entry, className);
		});
	}

}
