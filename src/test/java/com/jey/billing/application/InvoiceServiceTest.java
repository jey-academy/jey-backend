package com.jey.billing.application;

import static com.jey.core.member.MemberFixtures.mother;
import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.core.member.MemberFixtures.registration;
import static com.jey.core.member.MemberFixtures.uniqueName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.jey.billing.domain.DiscountPolicy;
import com.jey.billing.domain.InvoiceStatus;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.campus.ClassService;
import com.jey.core.member.MemberFixtures;
import com.jey.core.member.StudentService;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.Money;

// 데스크가 학생의 한 달 수강료를 계산하고(미리 계산), 청구로 남긴다. 금액은 계산기 그대로, 반·할인·학생은 이 서비스가 확인한다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
@WithJeyUser(id = InvoiceServiceTest.ADMIN_ID, role = UserRole.ADMIN)
class InvoiceServiceTest {

	static final long ADMIN_ID = 900_871L;

	private static final AuthenticatedUser ADMIN = new AuthenticatedUser(ADMIN_ID, "admin", "관리자", UserRole.ADMIN,
			null, null);

	private static final AuthenticatedUser DONGTAN_STAFF = new AuthenticatedUser(900_872L, "staff1", "직원",
			UserRole.STAFF, MemberFixtures.DONGTAN, null);

	private static final AuthenticatedUser NEUNGDONG_STAFF = new AuthenticatedUser(900_873L, "staff2", "직원",
			UserRole.STAFF, MemberFixtures.NEUNGDONG, null);

	private static final YearMonth NOVEMBER = YearMonth.of(2026, 11);

	@Autowired
	InvoiceService invoices;

	@Autowired
	StudentService students;

	@Autowired
	ClassService classes;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void 미리_계산은_금액만_주고_저장하지_않는다() {
		long student = newStudent();
		long cls = newClass(MemberFixtures.DONGTAN, 280_000);

		InvoiceDetail quote = invoices.quote(draft(student, line(cls, 9), "SIBLING_5"), DONGTAN_STAFF);

		assertThat(quote.id()).isNull();
		assertThat(quote.status()).isNull();
		assertThat(quote.totalAmount()).isEqualTo(Money.of(299_250));
		assertThat(quote.lines()).singleElement().satisfies(line -> {
			assertThat(line.listAmount()).isEqualTo(Money.of(315_000));
			assertThat(line.discounts()).singleElement().satisfies(discount -> {
				assertThat(discount.code()).isEqualTo("SIBLING_5");
				assertThat(discount.name()).isEqualTo("형제 할인 5%");
				assertThat(discount.amount()).isEqualTo(Money.of(15_750));
			});
		});
		assertThat(invoiceCount(student)).isZero();
	}

	@Test
	void 청구를_만들면_미리_계산과_같은_금액이_남고_감사_로그가_한_줄_남는다() {
		long student = newStudent();
		long cls = newClass(MemberFixtures.DONGTAN, 280_000);
		InvoiceDraft draft = draft(student, line(cls, 9), "SIBLING_5");

		InvoiceDetail issued = invoices.issue(draft, DONGTAN_STAFF);

		assertThat(issued.id()).isNotNull();
		assertThat(issued.status()).isEqualTo(InvoiceStatus.ISSUED);
		assertThat(issued.campusId()).isEqualTo(MemberFixtures.DONGTAN);
		assertThat(issued.discountPolicy()).isEqualTo(DiscountPolicy.ADDITIVE);
		assertThat(issued.totalAmount()).isEqualTo(invoices.quote(draft, DONGTAN_STAFF).totalAmount());
		assertThat(invoices.get(issued.id(), DONGTAN_STAFF)).isEqualTo(issued);
		assertThat(jdbc.queryForList("SELECT target_type, target_id, campus_id FROM core_audit_log "
				+ "WHERE action = 'billing.invoice-created' AND target_id = ?", student))
			.singleElement()
			.satisfies(row -> {
				assertThat(row.get("target_type")).isEqualTo("student");
				assertThat(((Number) row.get("campus_id")).longValue()).isEqualTo(MemberFixtures.DONGTAN);
			});
	}

	@Test
	void 반을_나눠_들으면_라인이_둘이고_합계는_두_라인의_합이다() {
		long student = newStudent();
		long first = newClass(MemberFixtures.DONGTAN, 280_000);
		long second = newClass(MemberFixtures.DONGTAN, 133_000);

		InvoiceDetail issued = invoices.issue(
				new InvoiceDraft(student, NOVEMBER, List.of(line(first, 4), line(second, 5)), List.of()), ADMIN);

		// 280,000 × 4 ÷ 8 = 140,000 / 133,000 × 5 ÷ 8 = 83,125
		assertThat(issued.lines()).extracting(InvoiceDetail.LineDetail::chargeAmount)
			.containsExactly(Money.of(140_000), Money.of(83_125));
		assertThat(issued.totalAmount()).isEqualTo(Money.of(223_125));
	}

	@Test
	void 같은_학생의_같은_달은_한_번만_만든다() {
		long student = newStudent();
		long cls = newClass(MemberFixtures.DONGTAN, 280_000);
		invoices.issue(draft(student, line(cls, 8)), ADMIN);

		assertRejected(() -> invoices.issue(draft(student, line(cls, 8)), ADMIN), "BILLING_INVOICE_ALREADY_EXISTS");
		assertThat(invoiceCount(student)).isOne();
		// 다음 달은 된다.
		invoices.issue(new InvoiceDraft(student, NOVEMBER.plusMonths(1), List.of(line(cls, 8)), List.of()), ADMIN);
		assertThat(invoiceCount(student)).isEqualTo(2);
	}

	@Test
	void 퇴원한_학생에게는_만들지_않는다() {
		long student = newStudent();
		long cls = newClass(MemberFixtures.DONGTAN, 280_000);
		jdbc.update("UPDATE core_student SET status = 'WITHDRAWN' WHERE id = ?", student);

		assertRejected(() -> invoices.issue(draft(student, line(cls, 8)), ADMIN), "BILLING_STUDENT_NOT_ENROLLED");
		assertThat(invoiceCount(student)).isZero();
	}

	@Test
	void 없는_학생은_404다() {
		assertRejected(() -> invoices.quote(draft(Long.MAX_VALUE, line(1, 8)), ADMIN), "BILLING_STUDENT_NOT_FOUND");
	}

	@Test
	void 반은_있어야_하고_켜져_있어야_하고_학생과_같은_지점이어야_한다() {
		long student = newStudent();
		long other = newClass(MemberFixtures.NEUNGDONG, 280_000);
		long off = newClass(MemberFixtures.DONGTAN, 280_000);
		jdbc.update("UPDATE core_class SET active = FALSE WHERE id = ?", off);

		assertRejected(() -> invoices.issue(draft(student, line(Long.MAX_VALUE, 8)), ADMIN), "BILLING_CLASS_NOT_FOUND");
		assertRejected(() -> invoices.issue(draft(student, line(off, 8)), ADMIN), "BILLING_CLASS_INACTIVE");
		assertRejected(() -> invoices.issue(draft(student, line(other, 8)), ADMIN),
				"BILLING_CLASS_NOT_AT_STUDENT_CAMPUS");
		assertThat(invoiceCount(student)).isZero();
	}

	@Test
	void 할인_유형은_있어야_하고_켜져_있어야_한다() {
		long student = newStudent();
		long cls = newClass(MemberFixtures.DONGTAN, 280_000);
		String code = "TEST_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		jdbc.update("INSERT INTO core_discount_type (code, name, rate, active, created_at, updated_at) "
				+ "VALUES (?, '꺼진 할인', 0.1000, FALSE, NOW(6), NOW(6))", code);

		assertRejected(() -> invoices.issue(draft(student, line(cls, 8), "NO_SUCH_DISCOUNT"), ADMIN),
				"BILLING_DISCOUNT_TYPE_NOT_FOUND");
		assertRejected(() -> invoices.issue(draft(student, line(cls, 8), code), ADMIN),
				"BILLING_DISCOUNT_TYPE_INACTIVE");
		assertThat(invoiceCount(student)).isZero();
	}

	@Test
	void 할인율을_더해_100퍼센트_이상이면_거부한다() {
		long student = newStudent();
		long cls = newClass(MemberFixtures.DONGTAN, 280_000);
		String half = halfDiscount();
		String otherHalf = halfDiscount();

		assertRejected(() -> invoices.quote(draft(student, line(cls, 8), half, otherHalf), ADMIN),
				"BILLING_DISCOUNT_TOO_LARGE");
	}

	@Test
	void 다른_지점_직원은_그_학생의_청구를_만들거나_보지_못한다() {
		long student = newStudent();
		long cls = newClass(MemberFixtures.DONGTAN, 280_000);

		assertRejected(() -> invoices.quote(draft(student, line(cls, 8)), NEUNGDONG_STAFF), "AUTH_CAMPUS_NOT_ALLOWED");
		assertRejected(() -> invoices.issue(draft(student, line(cls, 8)), NEUNGDONG_STAFF), "AUTH_CAMPUS_NOT_ALLOWED");
		assertThat(invoiceCount(student)).isZero();

		long id = invoices.issue(draft(student, line(cls, 8)), DONGTAN_STAFF).id();
		assertRejected(() -> invoices.get(id, NEUNGDONG_STAFF), "AUTH_CAMPUS_NOT_ALLOWED");
		assertThat(invoices.get(id, ADMIN).id()).isEqualTo(id);
	}

	@Test
	void 없는_청구는_404다() {
		assertRejected(() -> invoices.get(Long.MAX_VALUE, ADMIN), "BILLING_INVOICE_NOT_FOUND");
	}

	private long newStudent() {
		return students.register(registration(uniqueName(), mother(randomPhone()))).id();
	}

	private long newClass(long campusId, long baseFee8) {
		return classes.create(campusId, "반-" + UUID.randomUUID().toString().substring(0, 8), Money.of(baseFee8),
				Money.ZERO).getId();
	}

	private String halfDiscount() {
		String code = "HALF_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		jdbc.update("INSERT INTO core_discount_type (code, name, rate, active, created_at, updated_at) "
				+ "VALUES (?, '반값', 0.5000, TRUE, NOW(6), NOW(6))", code);
		return code;
	}

	private long invoiceCount(long studentId) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM billing_invoice WHERE student_id = ?", Long.class, studentId);
	}

	private static InvoiceDraft draft(long studentId, InvoiceDraft.LineDraft line, String... discountCodes) {
		return new InvoiceDraft(studentId, NOVEMBER, List.of(line), List.of(discountCodes));
	}

	private static InvoiceDraft.LineDraft line(long classId, int lessonCount) {
		return new InvoiceDraft.LineDraft(classId, lessonCount);
	}

	private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
		assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
				e -> assertThat(e.getErrorCode().code()).isEqualTo(code));
	}

}
