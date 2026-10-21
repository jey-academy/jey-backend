package com.jey.billing.application;

import static com.jey.core.member.MemberFixtures.mother;
import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.core.member.MemberFixtures.registration;
import static com.jey.core.member.MemberFixtures.uniqueName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.billing.api.InvoiceDiscount;
import com.jey.billing.api.InvoiceIssuing;
import com.jey.billing.api.IssueInvoiceCommand;
import com.jey.billing.api.IssuedInvoice;
import com.jey.billing.api.PaymentMethod;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.campus.ClassService;
import com.jey.core.member.MemberFixtures;
import com.jey.core.member.StudentService;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.Money;

// 다른 모듈(동탄고 패스)이 근거(참조값)가 있는 할인을 실어 청구를 만든다. 그런 할인은 이 창구로만 붙고, 직원이 고르는 일반 경로로는 붙지 않는다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
@WithJeyUser(id = InvoiceIssuingTest.STAFF_ID, role = UserRole.STAFF, campusId = MemberFixtures.DONGTAN)
class InvoiceIssuingTest {

	static final long STAFF_ID = 900_911L;

	private static final AuthenticatedUser STAFF = new AuthenticatedUser(STAFF_ID, "staff", "직원", UserRole.STAFF,
			MemberFixtures.DONGTAN, null);

	private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);

	@Autowired
	InvoiceIssuing issuing;

	@Autowired
	InvoiceService invoices;

	@Autowired
	PaymentService payments;

	@Autowired
	StudentService students;

	@Autowired
	ClassService classes;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void 넘긴_할인율로_계산하고_참조값을_청구에_남긴다() {
		long student = newStudent();
		long cls = newClass();

		IssuedInvoice issued = issuing.issue(command(student, cls, List.of(),
				new InvoiceDiscount("DONGTAN_PASS", new BigDecimal("0.0725"), "DT-ABC234")));

		// 280,000 × (1 − 0.0725) × 8 ÷ 8 = 259,700
		assertThat(issued.totalAmount()).isEqualTo(Money.of(259_700));
		assertThat(jdbc.queryForMap("SELECT d.discount_type_code, d.rate, d.discount_ref, d.amount "
				+ "FROM billing_invoice_line_discount d JOIN billing_invoice_line l ON l.id = d.line_id "
				+ "WHERE l.invoice_id = ?", issued.invoiceId()))
			.satisfies(row -> {
				assertThat(row.get("discount_type_code")).isEqualTo("DONGTAN_PASS");
				assertThat((BigDecimal) row.get("rate")).isEqualByComparingTo("0.0725");
				assertThat(row.get("discount_ref")).isEqualTo("DT-ABC234");
				assertThat(((Number) row.get("amount")).longValue()).isEqualTo(20_300);
			});
		assertThat(invoices.get(issued.invoiceId(), STAFF).lines().get(0).discounts()).singleElement()
			.satisfies(discount -> assertThat(discount.name()).isEqualTo("동탄고 패스"));
	}

	// 순차 방식에서는 순서가 금액을 바꾼다. 근거가 있는 할인(패스)을 직원이 고른 할인보다 먼저 계산한다.
	@Test
	void 근거가_있는_할인을_직원이_고른_할인보다_먼저_계산한다() {
		long student = newStudent();
		long cls = newClass();

		IssuedInvoice issued = issuing.issue(command(student, cls, List.of("SIBLING_5"),
				new InvoiceDiscount("DONGTAN_PASS", new BigDecimal("0.05"), "DT-ABC235")));

		assertThat(issued.totalAmount()).isEqualTo(Money.of(252_000));
		assertThat(jdbc.queryForList("SELECT d.discount_type_code FROM billing_invoice_line_discount d "
				+ "JOIN billing_invoice_line l ON l.id = d.line_id WHERE l.invoice_id = ? ORDER BY d.sort_order",
				String.class, issued.invoiceId()))
			.containsExactly("DONGTAN_PASS", "SIBLING_5");
	}

	// 장면: 이것이 없으면 직원이 패스 없는 학생에게 패스 할인을 골라도 막지 못하고, 청구에 근거(패스 번호)가 남지 않는다.
	@Test
	void 근거가_필요한_할인은_직원이_고르는_경로로_만들지_못하고_미리_계산만_된다() {
		long student = newStudent();
		long cls = newClass();
		InvoiceDraft draft = new InvoiceDraft(student, NOVEMBER, List.of(new InvoiceDraft.LineDraft(cls, 8)),
				List.of("DONGTAN_PASS"));

		assertThatThrownBy(() -> invoices.issue(draft, STAFF)).isInstanceOfSatisfying(BusinessException.class,
				e -> assertThat(e.getErrorCode().code()).isEqualTo("BILLING_DISCOUNT_NEEDS_REFERENCE"));
		assertThatThrownBy(() -> issuing.issue(new IssueInvoiceCommand(student, NOVEMBER,
				List.of(new IssueInvoiceCommand.Line(cls, 8)), List.of("DONGTAN_PASS"), List.of())))
			.isInstanceOfSatisfying(BusinessException.class,
					e -> assertThat(e.getErrorCode().code()).isEqualTo("BILLING_DISCOUNT_NEEDS_REFERENCE"));
		assertThat(invoices.quote(draft, STAFF).totalAmount()).isEqualTo(Money.of(266_000));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM billing_invoice WHERE student_id = ?", Long.class,
				student)).isZero();
	}

	// 할인 유형 코드는 DB에서 대소문자를 가리지 않고 찾는다. 보호도 찾은 코드로 본다(리뷰 지적: 소문자로 보내면 지나갔다).
	@Test
	void 근거가_필요한_할인을_소문자로_보내도_막는다() {
		long student = newStudent();
		long cls = newClass();

		assertThatThrownBy(() -> invoices.issue(new InvoiceDraft(student, NOVEMBER,
				List.of(new InvoiceDraft.LineDraft(cls, 8)), List.of("dongtan_pass")), STAFF))
			.isInstanceOfSatisfying(BusinessException.class,
					e -> assertThat(e.getErrorCode().code()).isEqualTo("BILLING_DISCOUNT_NEEDS_REFERENCE"));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM billing_invoice WHERE student_id = ?", Long.class,
				student)).isZero();
	}

	@Test
	void 대소문자만_다른_같은_할인은_400이다() {
		long student = newStudent();
		long cls = newClass();
		InvoiceDraft draft = new InvoiceDraft(student, NOVEMBER, List.of(new InvoiceDraft.LineDraft(cls, 8)),
				List.of("SIBLING_5", "sibling_5"));

		assertThatThrownBy(() -> invoices.quote(draft, STAFF)).isInstanceOfSatisfying(BusinessException.class,
				e -> assertThat(e.getErrorCode().code()).isEqualTo("BILLING_DISCOUNT_DUPLICATED"));
		// 근거 할인과 직원 할인이 같은 유형이어도 같다(패스 경로에서 패스 할인을 또 고른 경우는 위의 400이 먼저다).
		assertThatThrownBy(() -> issuing.issue(command(student, cls, List.of(),
				new InvoiceDiscount("DONGTAN_PASS", new BigDecimal("0.05"), "DT-1"),
				new InvoiceDiscount("dongtan_pass", new BigDecimal("0.05"), "DT-2"))))
			.isInstanceOfSatisfying(BusinessException.class,
					e -> assertThat(e.getErrorCode().code()).isEqualTo("BILLING_DISCOUNT_DUPLICATED"));
	}

	// 근거 할인 경로는 할인율을 부르는 쪽 값으로 쓴다. 근거가 필요한 할인이 아니면 받지 않는다
	// (다른 모듈의 버그 하나로 형제 할인이 30%로 청구되지 않게 — 리뷰 지적).
	@Test
	void 근거_할인_경로로_일반_할인을_넘기면_거부한다() {
		long student = newStudent();
		long cls = newClass();

		assertThatThrownBy(() -> issuing.issue(command(student, cls, List.of(),
				new InvoiceDiscount("SIBLING_5", new BigDecimal("0.3"), "X-1"))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM billing_invoice WHERE student_id = ?", Long.class,
				student)).isZero();
	}

	@Test
	void 일반_경로와_같이_한_학생_한_달에_열린_청구는_하나다() {
		long student = newStudent();
		long cls = newClass();
		issuing.issue(command(student, cls, List.of()));

		assertThatThrownBy(() -> issuing.issue(command(student, cls, List.of())))
			.isInstanceOfSatisfying(BusinessException.class,
					e -> assertThat(e.getErrorCode().code()).isEqualTo("BILLING_INVOICE_ALREADY_EXISTS"));
	}

	@Test
	void 열린_청구인지_알려_준다() {
		long cls = newClass();
		long issuedId = issuing.issue(command(newStudent(), cls, List.of())).invoiceId();
		long paidId = issuing.issue(command(newStudent(), cls, List.of())).invoiceId();
		payments.record(paidId, PaymentMethod.CASH, Money.of(280_000), Money.ZERO, STAFF);
		long canceledId = issuing.issue(command(newStudent(), cls, List.of())).invoiceId();
		invoices.cancel(canceledId, STAFF);

		assertThat(issuing.isOpen(issuedId)).isTrue();
		assertThat(issuing.isOpen(paidId)).isTrue();
		assertThat(issuing.isOpen(canceledId)).isFalse();
		assertThat(issuing.isOpen(Long.MAX_VALUE)).isFalse();
	}

	// 사용 내역 한 페이지의 청구가 열려 있는지 한 번에 묻는다(줄마다 묻지 않게).
	@Test
	void 여러_청구_가운데_열린_것만_한_번에_알려_준다() {
		long cls = newClass();
		long issuedId = issuing.issue(command(newStudent(), cls, List.of())).invoiceId();
		long paidId = issuing.issue(command(newStudent(), cls, List.of())).invoiceId();
		payments.record(paidId, PaymentMethod.CASH, Money.of(280_000), Money.ZERO, STAFF);
		long canceledId = issuing.issue(command(newStudent(), cls, List.of())).invoiceId();
		invoices.cancel(canceledId, STAFF);

		assertThat(issuing.openAmong(List.of(issuedId, paidId, canceledId, Long.MAX_VALUE)))
			.containsExactlyInAnyOrder(issuedId, paidId);
		assertThat(issuing.openAmong(List.of())).isEmpty();
	}

	@Test
	void 넘기는_할인은_코드_할인율_참조값이_규칙_안이어야_한다() {
		assertThatThrownBy(() -> new InvoiceDiscount(" ", new BigDecimal("0.05"), "DT-1"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new InvoiceDiscount("DONGTAN_PASS", new BigDecimal("1"), "DT-1"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new InvoiceDiscount("DONGTAN_PASS", new BigDecimal("0.05"), " "))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new InvoiceDiscount("DONGTAN_PASS", new BigDecimal("0.05"), "x".repeat(101)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private static IssueInvoiceCommand command(long student, long cls, List<String> codes,
			InvoiceDiscount... referenced) {
		return new IssueInvoiceCommand(student, NOVEMBER, List.of(new IssueInvoiceCommand.Line(cls, 8)), codes,
				List.of(referenced));
	}

	private long newStudent() {
		return students.register(registration(uniqueName(), mother(randomPhone()))).id();
	}

	private long newClass() {
		return classes.create(MemberFixtures.DONGTAN, "반-" + UUID.randomUUID().toString().substring(0, 8),
				Money.of(280_000), Money.ZERO).getId();
	}

}
