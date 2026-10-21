package com.jey.billing.application;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.LongConsumer;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.jey.billing.api.InvoiceDiscount;
import com.jey.billing.api.InvoiceIssuing;
import com.jey.billing.api.IssueInvoiceCommand;
import com.jey.billing.api.IssuedInvoice;
import com.jey.billing.domain.DiscountRate;
import com.jey.billing.domain.DiscountTooLargeException;
import com.jey.billing.domain.Invoice;
import com.jey.billing.domain.InvoiceLine;
import com.jey.billing.domain.InvoiceLineDiscount;
import com.jey.billing.domain.InvoiceRepository;
import com.jey.billing.domain.InvoiceStateException;
import com.jey.billing.domain.InvoiceStatus;
import com.jey.billing.domain.PaymentRepository;
import com.jey.billing.domain.PaymentStatus;
import com.jey.billing.domain.LineCharge;
import com.jey.billing.domain.TooManyDiscountsException;
import com.jey.billing.domain.TuitionCalculator;
import com.jey.core.audit.api.AuditEntry;
import com.jey.core.audit.api.AuditLog;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.CampusScope;
import com.jey.core.campus.api.CampusCatalog;
import com.jey.core.campus.api.ClassFee;
import com.jey.core.campus.api.ClassInfo;
import com.jey.core.campus.api.DiscountTypeInfo;
import com.jey.core.member.api.MemberDirectory;
import com.jey.core.member.api.MemberRefs;
import com.jey.core.member.api.StudentInfo;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.Money;
import com.jey.core.shared.UniqueKeys;

/**
 * 학생의 한 달 수강료를 계산하고(미리 계산) 청구로 남기고, 결제 전 청구를 취소한다. 데스크 직원과 관리자가 부른다(컨트롤러가 막는다).
 * 다른 모듈은 {@link InvoiceIssuing}으로 근거가 있는 할인을 실어 만든다(지점은 부르는 쪽이 본다).
 *
 * <p>청구는 만들 때 학생의 지점에 속한다(직원은 소속 지점의 학생만). 학생은 재원 중이어야 하고, 반은 켜져 있고 학생과 같은 지점이어야 하며,
 * 할인 유형은 켜져 있어야 한다. 할인 유형 코드는 대소문자를 가리지 않고 찾으므로, 근거가 필요한지·겹치는지는 찾은 코드로 본다.
 * 금액은 {@link TuitionCalculator}가 설정의 할인 방식({@link BillingProperties})으로 계산한다.
 * 한 학생 한 달에 열린 청구는 하나다(유니크 키가 마지막에 막는다 — 동시 요청도).
 */
@Service
@EnableConfigurationProperties(BillingProperties.class)
public class InvoiceService implements InvoiceIssuing {

	private final InvoiceRepository invoices;

	private final CampusCatalog catalog;

	private final MemberDirectory members;

	private final BillingProperties properties;

	private final AuditLog auditLog;

	private final TransactionTemplate transaction;

	private final PaymentRepository payments;

	private final StudentNames studentNames;

	// 다른 모듈이 부른 경로: 누가 그 학생을 다룰 수 있는지는 부르는 쪽이 이미 보았다.
	private static final LongConsumer CHECKED_BY_CALLER = campusId -> {
	};

	InvoiceService(InvoiceRepository invoices, CampusCatalog catalog, MemberDirectory members,
			BillingProperties properties, AuditLog auditLog, TransactionTemplate transaction,
			PaymentRepository payments, StudentNames studentNames) {
		this.invoices = invoices;
		this.studentNames = studentNames;
		this.payments = payments;
		this.catalog = catalog;
		this.members = members;
		this.properties = properties;
		this.auditLog = auditLog;
		this.transaction = transaction;
	}

	/**
	 * 저장하지 않고 계산만 한다.
	 *
	 * @throws BusinessException 학생·반·할인이 규칙 밖이거나, 다룰 수 없는 지점의 학생일 때
	 */
	@Transactional(readOnly = true)
	public InvoiceDetail quote(InvoiceDraft draft, AuthenticatedUser user) {
		Objects.requireNonNull(user, "user");
		// 미리 계산은 저장하지 않으므로 근거가 필요한 할인도 넣어 볼 수 있다(패스 학생의 금액을 미리 보여 준다).
		Prepared prepared = prepare(draft, user::requireCampus, true);
		List<InvoiceDetail.LineDetail> lines = new ArrayList<>();
		for (PreparedLine line : prepared.lines()) {
			LineCharge charge = charge(line, prepared.discounts());
			lines.add(lineDetail(line.info(), line.draft().lessonCount(), charge.listAmount(),
					charge.discounts().stream()
						.map(applied -> new InvoiceDetail.DiscountDetail(applied.code(), prepared.nameOf(applied.code()),
								applied.rate(), applied.amount()))
						.toList(),
					charge.chargeAmount()));
		}
		return new InvoiceDetail(null, prepared.student().campusId(), draft.studentId(), prepared.student().name(),
				draft.targetMonth(), null,
				properties.discountPolicy(),
				lines.stream().map(InvoiceDetail.LineDetail::chargeAmount).reduce(Money.ZERO, Money::plus), lines, null);
	}

	/**
	 * 직원이 청구를 만든다. 감사 로그({@code billing.invoice-created})가 같은 트랜잭션에 남는다.
	 * 근거가 필요한 할인(설정 {@code jey.billing.referenced-discount-codes})은 고를 수 없다
	 * (그 할인을 준 모듈이 {@link #issue(IssueInvoiceCommand)}로 붙인다).
	 *
	 * @throws BusinessException {@link #quote}의 거부에 더해, 근거가 필요한 할인을 골랐거나 그 학생의 그 달 청구가 이미 있을 때
	 */
	public InvoiceDetail issue(InvoiceDraft draft, AuthenticatedUser user) {
		Objects.requireNonNull(user, "user");
		return save(draft, user::requireCampus);
	}

	@Override
	public IssuedInvoice issue(IssueInvoiceCommand command) {
		InvoiceDraft draft = new InvoiceDraft(command.studentId(), command.targetMonth(),
				command.lines().stream().map(line -> new InvoiceDraft.LineDraft(line.classId(), line.lessonCount()))
					.toList(),
				command.discountCodes(), command.referencedDiscounts());
		InvoiceDetail saved = save(draft, CHECKED_BY_CALLER);
		return new IssuedInvoice(saved.id(), saved.totalAmount());
	}

	@Override
	@Transactional(readOnly = true)
	public boolean isOpen(long invoiceId) {
		return invoices.findById(invoiceId).map(Invoice::isOpen).orElse(false);
	}

	@Override
	@Transactional(readOnly = true)
	public OptionalLong campusOfOpen(long invoiceId) {
		return invoices.findById(invoiceId)
			.filter(Invoice::isOpen)
			.map(invoice -> OptionalLong.of(invoice.getCampusId()))
			.orElse(OptionalLong.empty());
	}

	@Override
	@Transactional(readOnly = true)
	public Set<Long> openAmong(Collection<Long> invoiceIds) {
		Objects.requireNonNull(invoiceIds, "청구 ID 목록이 없다");
		invoiceIds.forEach(id -> Objects.requireNonNull(id, "청구 ID 목록에 null이 있다"));
		if (invoiceIds.isEmpty()) {
			return Set.of();
		}
		return invoices.findAllById(Set.copyOf(invoiceIds))
			.stream()
			.filter(Invoice::isOpen)
			.map(Invoice::getId)
			.collect(Collectors.toUnmodifiableSet());
	}

	// 부르는 쪽이 바깥 트랜잭션을 열었으면 그 안에서 돈다. 여기서 예외가 나면 바깥 트랜잭션은 롤백으로 표시된다
	// (잡고 계속하면 커밋할 때 실패한다 — InvoiceIssuing의 문서).
	private InvoiceDetail save(InvoiceDraft draft, LongConsumer campusCheck) {
		try {
			return transaction.execute(status -> {
				Prepared prepared = prepare(draft, campusCheck, false);
				List<InvoiceLine> lines = prepared.lines().stream()
					.map(line -> InvoiceLine.of(line.fee().classId(), line.draft().lessonCount(), line.fee().baseFee8(),
							charge(line, prepared.discounts())))
					.toList();
				// 그 달 청구가 이미 있으면 409. 그 청구가 학생의 지금 지점(위에서 권한을 확인한 지점)에 속할 때만 ID를 싣는다
				// — 학생이 지점을 옮겼으면 옛 지점의 청구라 요청한 사람이 열 수 없다. 동시 요청이 이 확인을 함께 지나면
				// 유니크 키가 막는다 — 그때도 ID 없이 409다(오류 난 세션으로 다시 읽지 않는다).
				invoices.findOpen(draft.studentId(), draft.targetMonth()).ifPresent(existing -> {
					BusinessException conflict = new BusinessException(BillingErrorCode.INVOICE_ALREADY_EXISTS);
					if (existing.campusId() == prepared.student().campusId()) {
						conflict.withId("invoiceId", existing.id());
					}
					throw conflict;
				});
				Invoice saved = invoices.saveAndFlush(Invoice.issue(prepared.student().campusId(), draft.studentId(),
						draft.targetMonth(), properties.discountPolicy(), lines));
				auditLog.record(AuditEntry.of(BillingAuditAction.INVOICE_CREATED)
					.target(MemberRefs.student(saved.getStudentId()))
					.campus(saved.getCampusId())
					.with("invoiceId", saved.getId())
					.with("targetMonth", saved.getTargetMonth().toString())
					.with("totalAmount", saved.getTotalAmount().amount().longValueExact()));
				return detail(saved, prepared.student().name());
			});
		}
		catch (DataIntegrityViolationException e) {
			if (UniqueKeys.isViolationOf(e, Invoice.OPEN_UNIQUE_KEY)) {
				throw new BusinessException(BillingErrorCode.INVOICE_ALREADY_EXISTS);
			}
			throw e;
		}
	}

	/**
	 * @throws BusinessException 없는 청구이거나 다룰 수 없는 지점의 청구일 때
	 */
	@Transactional(readOnly = true)
	public InvoiceDetail get(long invoiceId, AuthenticatedUser user) {
		Invoice invoice = invoices.findById(invoiceId)
			.orElseThrow(() -> new BusinessException(BillingErrorCode.INVOICE_NOT_FOUND));
		user.requireCampus(invoice.getCampusId());
		return detail(invoice, studentNames.nameOf(invoice.getStudentId()));
	}

	/**
	 * 결제 전 청구를 취소한다. 닫힌 청구가 되어 같은 달에 새 청구를 만들 수 있다. 감사 로그({@code billing.invoice-canceled})가 남는다.
	 *
	 * @throws BusinessException 없는 청구, 다룰 수 없는 지점, 결제 전이 아닌 청구(결제한 청구는 결제를 먼저 취소한다)
	 */
	public InvoiceDetail cancel(long invoiceId, AuthenticatedUser user) {
		return transaction.execute(status -> {
			Invoice invoice = invoices.findByIdForUpdate(invoiceId)
				.orElseThrow(() -> new BusinessException(BillingErrorCode.INVOICE_NOT_FOUND));
			user.requireCampus(invoice.getCampusId());
			try {
				invoice.cancel();
			}
			catch (InvoiceStateException e) {
				throw new BusinessException(BillingErrorCode.INVOICE_NOT_CANCELABLE);
			}
			auditLog.record(AuditEntry.of(BillingAuditAction.INVOICE_CANCELED)
				.target(MemberRefs.student(invoice.getStudentId()))
				.campus(invoice.getCampusId())
				.with("invoiceId", invoice.getId()));
			return detail(invoice, studentNames.nameOf(invoice.getStudentId()));
		});
	}

	/** 그 달의 청구. 학생 ID 순. */
	@Transactional(readOnly = true)
	public Page<InvoiceDetail> list(CampusScope scope, YearMonth targetMonth, int page, int size) {
		PageRequest pageable = PageRequest.of(page, size, Sort.by("studentId", "id"));
		Page<Invoice> found = switch (scope) {
			case CampusScope.All all -> invoices.findByTargetMonth(targetMonth, pageable);
			case CampusScope.Only only -> invoices.findByCampusIdAndTargetMonth(only.campusId(), targetMonth, pageable);
		};
		Map<Long, String> names = studentNames.namesOf(found.map(Invoice::getStudentId).getContent());
		return found.map(invoice -> detail(invoice, names.get(invoice.getStudentId())));
	}

	/**
	 * @param campusCheck 학생의 지점을 다룰 수 있는지 본다(직원·관리자는 {@code user::requireCampus}, 다른 모듈은 {@link #CHECKED_BY_CALLER})
	 * @param referencedCodesAllowed 직원이 고른 할인에 근거가 필요한 할인이 있어도 되는지(미리 계산만)
	 */
	private Prepared prepare(InvoiceDraft draft, LongConsumer campusCheck, boolean referencedCodesAllowed) {
		StudentInfo student = members.findStudent(draft.studentId())
			.orElseThrow(() -> new BusinessException(BillingErrorCode.STUDENT_NOT_FOUND));
		campusCheck.accept(student.campusId());
		if (!student.enrolled()) {
			throw new BusinessException(BillingErrorCode.STUDENT_NOT_ENROLLED);
		}
		List<PreparedLine> lines = new ArrayList<>();
		for (InvoiceDraft.LineDraft line : draft.lines()) {
			ClassInfo info = catalog.findClass(line.classId())
				.orElseThrow(() -> new BusinessException(BillingErrorCode.CLASS_NOT_FOUND));
			ClassFee fee = catalog.findClassFee(line.classId())
				.orElseThrow(() -> new BusinessException(BillingErrorCode.CLASS_NOT_FOUND));
			if (!fee.active()) {
				throw new BusinessException(BillingErrorCode.CLASS_INACTIVE);
			}
			if (fee.campusId() != student.campusId()) {
				throw new BusinessException(BillingErrorCode.CLASS_NOT_AT_STUDENT_CAMPUS);
			}
			lines.add(new PreparedLine(line, info, fee));
		}
		// 근거가 있는 할인을 먼저, 직원이 고른 할인을 나중에 계산한다(순차 방식에서 순서가 금액을 바꾼다).
		// 코드는 찾은 할인 유형의 코드로 본다(DB는 대소문자를 가리지 않고 찾는다 — 소문자로 보내 보호를 지나가지 않게).
		List<ChosenDiscount> discounts = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (InvoiceDiscount referenced : draft.referencedDiscounts()) {
			DiscountTypeInfo type = activeDiscountType(referenced.discountType());
			if (!properties.needsReference(type.code())) {
				// 할인율을 부르는 쪽 값으로 쓰는 길이라, 근거가 필요한 할인만 받는다(부르는 쪽의 버그).
				throw new IllegalArgumentException("근거가 필요한 할인이 아니다: " + type.code());
			}
			requireFirst(seen, type.code());
			discounts.add(new ChosenDiscount(type.code(), type.name(), referenced.discountRate(),
					referenced.discountRef()));
		}
		for (String code : draft.discountCodes()) {
			DiscountTypeInfo type = activeDiscountType(code);
			if (!referencedCodesAllowed && properties.needsReference(type.code())) {
				throw new BusinessException(BillingErrorCode.DISCOUNT_NEEDS_REFERENCE);
			}
			requireFirst(seen, type.code());
			discounts.add(new ChosenDiscount(type.code(), type.name(), type.rate(), null));
		}
		return new Prepared(student, lines, discounts);
	}

	private static void requireFirst(Set<String> seen, String code) {
		if (!seen.add(code)) {
			throw new BusinessException(BillingErrorCode.DISCOUNT_DUPLICATED);
		}
	}

	private DiscountTypeInfo activeDiscountType(String code) {
		DiscountTypeInfo type = catalog.findDiscountType(code)
			.orElseThrow(() -> new BusinessException(BillingErrorCode.DISCOUNT_TYPE_NOT_FOUND));
		if (!type.active()) {
			throw new BusinessException(BillingErrorCode.DISCOUNT_TYPE_INACTIVE);
		}
		return type;
	}

	private LineCharge charge(PreparedLine line, List<ChosenDiscount> discounts) {
		List<DiscountRate> rates = discounts.stream()
			.map(discount -> new DiscountRate(discount.code(), discount.rate(), discount.ref()))
			.toList();
		try {
			return TuitionCalculator.charge(line.fee().baseFee8(), line.draft().lessonCount(), rates,
					properties.discountPolicy());
		}
		catch (TooManyDiscountsException e) {
			throw new BusinessException(BillingErrorCode.TOO_MANY_DISCOUNTS);
		}
		catch (DiscountTooLargeException e) {
			throw new BusinessException(BillingErrorCode.DISCOUNT_TOO_LARGE);
		}
	}

	// 저장한 청구에 반·할인의 지금 이름을 붙인다. 반과 할인 유형은 지우지 않으므로 없으면 자료가 깨진 것이다.
	private InvoiceDetail detail(Invoice invoice, String studentName) {
		List<InvoiceDetail.LineDetail> lines = invoice.getLines().stream()
			.map(line -> lineDetail(
					catalog.findClass(line.getClassId())
						.orElseThrow(() -> new IllegalStateException("청구 라인의 반이 없다: " + line.getClassId())),
					line.getLessonCount(), line.getListAmount(),
					line.getDiscounts().stream().map(this::discountDetail).toList(), line.getChargeAmount()))
			.toList();
		return new InvoiceDetail(invoice.getId(), invoice.getCampusId(), invoice.getStudentId(), studentName,
				invoice.getTargetMonth(), invoice.getStatus(), invoice.getDiscountPolicy(), invoice.getTotalAmount(),
				lines, payments.findByInvoiceIdAndStatus(invoice.getId(), PaymentStatus.COMPLETED).map(payment -> PaymentView.of(payment, studentName))
					.orElse(null));
	}

	private InvoiceDetail.DiscountDetail discountDetail(InvoiceLineDiscount discount) {
		String name = catalog.findDiscountType(discount.getDiscountTypeCode())
			.map(DiscountTypeInfo::name)
			.orElseThrow(() -> new IllegalStateException("청구의 할인 유형이 없다: " + discount.getDiscountTypeCode()));
		return new InvoiceDetail.DiscountDetail(discount.getDiscountTypeCode(), name, discount.getRate(),
				discount.getAmount());
	}

	private static InvoiceDetail.LineDetail lineDetail(ClassInfo info, int lessonCount,
			Money listAmount, List<InvoiceDetail.DiscountDetail> discounts,
			Money chargeAmount) {
		return new InvoiceDetail.LineDetail(info.classId(), info.name(), lessonCount, listAmount, discounts,
				chargeAmount);
	}

	private record Prepared(StudentInfo student, List<PreparedLine> lines, List<ChosenDiscount> discounts) {

		String nameOf(String code) {
			return discounts.stream().filter(discount -> discount.code().equals(code)).findFirst().orElseThrow().name();
		}

	}

	// 이 청구에 붙일 할인 하나. 할인율은 근거가 있는 할인이면 넘겨받은 값, 직원이 고른 할인이면 할인 유형의 지금 값이다.
	private record ChosenDiscount(String code, String name, BigDecimal rate, String ref) {
	}

	private record PreparedLine(InvoiceDraft.LineDraft draft, ClassInfo info, ClassFee fee) {
	}

}
