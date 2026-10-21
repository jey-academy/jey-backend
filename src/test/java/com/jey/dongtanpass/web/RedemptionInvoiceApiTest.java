package com.jey.dongtanpass.web;

import static com.jey.TestCsrf.csrfToken;
import static com.jey.TestIdempotency.idempotencyKey;
import static com.jey.dongtanpass.DongtanpassFixtures.upsertPartner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.campus.ClassService;
import com.jey.core.member.MemberFixtures;
import com.jey.core.member.StudentService;
import com.jey.core.shared.Money;
import com.jey.core.shared.PhoneNumber;
import com.jey.core.shared.PhoneProtector;
import com.jey.dongtanpass.application.PassRedeemService;
import com.jey.dongtanpass.domain.LinkToken;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassNumber;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.PaymentMethod;

// 패스를 쓴 학생의 청구는 사용 기록에서 만든다. 패스 할인(지금 할인율, 참조값 = 패스 번호)이 붙고, 사용 기록 하나에 열린 청구 하나다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithJeyUser(id = RedemptionInvoiceApiTest.STAFF_ID, role = UserRole.STAFF, campusId = 1)
class RedemptionInvoiceApiTest {

	static final long STAFF_ID = 901_921L;

	private static final AuthenticatedUser STAFF = new AuthenticatedUser(STAFF_ID, "staff", "직원", UserRole.STAFF, 1L,
			null);

	private static final long PARTNER = 901_922L;

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private static final SecureRandom RANDOM = new SecureRandom();

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PassRepository passes;

	@Autowired
	PassRedeemService redeemService;

	@Autowired
	StudentService students;

	@Autowired
	ClassService classes;

	@Autowired
	PhoneProtector phoneProtector;

	@Autowired
	Clock clock;

	private long cls;

	@BeforeEach
	void setUp() {
		upsertPartner(jdbc, PARTNER, true);
		jdbc.update("UPDATE core_discount_type SET rate = 0.0500, active = TRUE WHERE code = 'DONGTAN_PASS'");
		cls = classes.create(1, "반-" + UUID.randomUUID().toString().substring(0, 8), Money.of(280_000), Money.ZERO)
			.getId();
	}

	@Test
	void 사용_기록에서_패스_할인을_붙인_청구를_만든다() throws Exception {
		Redeemed redeemed = redeemed();

		long invoiceId = idOf(issue(redeemed.redemptionId(), 9, "").andExpect(status().isCreated())
			.andExpect(jsonPath("$.redemptionId").value(redeemed.redemptionId()))
			.andExpect(jsonPath("$.totalAmount").value(299250)));

		assertThat(jdbc.queryForMap("SELECT d.discount_type_code, d.discount_ref, d.amount "
				+ "FROM billing_invoice_line_discount d JOIN billing_invoice_line l ON l.id = d.line_id "
				+ "WHERE l.invoice_id = ?", invoiceId))
			.containsEntry("discount_type_code", "DONGTAN_PASS")
			.containsEntry("discount_ref", redeemed.passNo());
		assertThat(jdbc.queryForObject("SELECT invoice_id FROM dongtanpass_redemption WHERE id = ?", Long.class,
				redeemed.redemptionId())).isEqualTo(invoiceId);
	}

	@Test
	void 직원이_고른_할인을_더하면_패스_할인을_먼저_계산한다() throws Exception {
		Redeemed redeemed = redeemed();

		long invoiceId = idOf(issue(redeemed.redemptionId(), 8, "\"SIBLING_5\"").andExpect(status().isCreated())
			.andExpect(jsonPath("$.totalAmount").value(252000)));

		assertThat(jdbc.queryForList("SELECT d.discount_type_code FROM billing_invoice_line_discount d "
				+ "JOIN billing_invoice_line l ON l.id = d.line_id WHERE l.invoice_id = ? ORDER BY d.sort_order",
				String.class, invoiceId))
			.containsExactly("DONGTAN_PASS", "SIBLING_5");
	}

	@Test
	void 열린_청구가_있으면_409이고_청구를_취소하면_다시_만든다() throws Exception {
		Redeemed redeemed = redeemed();
		long first = idOf(issue(redeemed.redemptionId(), 8, "").andExpect(status().isCreated()));

		issue(redeemed.redemptionId(), 8, "").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_REDEMPTION_ALREADY_INVOICED"))
			// 화면이 "그 청구 열기"로 바로 이어 간다.
			.andExpect(jsonPath("$.invoiceId").value(first));
		mockMvc.perform(post("/api/v1/billing/invoices/" + first + "/cancel").with(csrfToken(mockMvc))
				.with(idempotencyKey()))
			.andExpect(status().isOk());

		long second = idOf(issue(redeemed.redemptionId(), 9, "").andExpect(status().isCreated()));
		assertThat(second).isNotEqualTo(first);
		assertThat(jdbc.queryForObject("SELECT invoice_id FROM dongtanpass_redemption WHERE id = ?", Long.class,
				redeemed.redemptionId())).isEqualTo(second);
	}

	@Test
	void 수납이_거부하면_사용_기록은_그대로다() throws Exception {
		Redeemed redeemed = redeemed();
		jdbc.update("UPDATE core_class SET active = FALSE WHERE id = ?", cls);

		issue(redeemed.redemptionId(), 8, "").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("BILLING_CLASS_INACTIVE"));
		assertThat(jdbc.queryForObject("SELECT invoice_id FROM dongtanpass_redemption WHERE id = ?", Long.class,
				redeemed.redemptionId())).isNull();
	}

	@Test
	void 없는_사용_기록은_404다() throws Exception {
		issue(Long.MAX_VALUE, 8, "").andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_REDEMPTION_NOT_FOUND"));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 2)
	void 다른_지점_직원은_만들지_못한다() throws Exception {
		Redeemed redeemed = redeemed();

		issue(redeemed.redemptionId(), 8, "").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH_CAMPUS_NOT_ALLOWED"));
		assertThat(jdbc.queryForObject("SELECT invoice_id FROM dongtanpass_redemption WHERE id = ?", Long.class,
				redeemed.redemptionId())).isNull();
	}

	// 청구는 학생의 지금 지점에 속한다. 사용 처리 뒤 학생이 다른 지점으로 옮겼으면, 사용 기록의 지점 직원만으로는
	// 그 지점의 청구를 만들지 못한다(보안 검사 지적: 확인한 지점과 쓰는 지점이 달랐다).
	@Test
	void 사용_뒤_학생의_지점이_바뀌었으면_사용_기록의_지점_직원은_만들지_못한다() throws Exception {
		Redeemed redeemed = redeemed();
		long student = jdbc.queryForObject("SELECT student_id FROM dongtanpass_redemption WHERE id = ?", Long.class,
				redeemed.redemptionId());
		jdbc.update("UPDATE core_student SET campus_id = 2 WHERE id = ?", student);

		issue(redeemed.redemptionId(), 8, "").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("AUTH_CAMPUS_NOT_ALLOWED"));
		assertThat(jdbc.queryForObject("SELECT invoice_id FROM dongtanpass_redemption WHERE id = ?", Long.class,
				redeemed.redemptionId())).isNull();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM billing_invoice WHERE student_id = ?", Long.class,
				student)).isZero();
	}

	// 수납의 일반 경로로 그 달 청구를 먼저 만든 경우. 패스 경로는 바깥 트랜잭션 안에서 수납을 부르므로,
	// 409로 끝나고 사용 기록도 그대로인지 본다(트랜잭션을 함께 쓰는 경로). 409에는 이미 있는 청구의 ID가 있다.
	@Test
	void 그_달_청구가_이미_있으면_409이고_사용_기록은_그대로다() throws Exception {
		Redeemed redeemed = redeemed();
		long student = studentOf(redeemed);
		String existing = mockMvc.perform(post("/api/v1/billing/invoices").with(csrfToken(mockMvc)).with(idempotencyKey())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"studentId\": %d, \"targetMonth\": \"2026-11\", \"lines\": [{\"classId\": %d, \"lessonCount\": 8}]}"
					.formatted(student, cls)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		long existingId = Long.parseLong(existing.replaceAll("(?s).*?\"id\":(\\d+).*", "$1"));

		issue(redeemed.redemptionId(), 8, "").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("BILLING_INVOICE_ALREADY_EXISTS"))
			.andExpect(jsonPath("$.invoiceId").value(existingId));
		assertThat(jdbc.queryForObject("SELECT invoice_id FROM dongtanpass_redemption WHERE id = ?", Long.class,
				redeemed.redemptionId())).isNull();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM billing_invoice WHERE student_id = ?", Long.class,
				student)).isOne();
	}

	// 패스 할인은 서버가 붙인다. 직원이 추가 할인에 또 고르면(대소문자가 달라도) 400이고 청구는 없다.
	@Test
	void 추가_할인에_패스_할인을_또_고르면_400이다() throws Exception {
		Redeemed redeemed = redeemed();

		issue(redeemed.redemptionId(), 8, "\"DONGTAN_PASS\"").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("BILLING_DISCOUNT_NEEDS_REFERENCE"));
		issue(redeemed.redemptionId(), 8, "\"dongtan_pass\"").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("BILLING_DISCOUNT_NEEDS_REFERENCE"));
		assertThat(jdbc.queryForObject("SELECT invoice_id FROM dongtanpass_redemption WHERE id = ?", Long.class,
				redeemed.redemptionId())).isNull();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM billing_invoice WHERE student_id = ?", Long.class,
				studentOf(redeemed))).isZero();
	}

	// 꺼진 패스 할인은 무엇이 꺼졌는지 알려 준다(데스크 사용 처리와 같은 코드).
	@Test
	void 패스_할인이_꺼졌으면_409다() throws Exception {
		Redeemed redeemed = redeemed();
		jdbc.update("UPDATE core_discount_type SET active = FALSE WHERE code = 'DONGTAN_PASS'");
		try {
			issue(redeemed.redemptionId(), 8, "").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("DONGTANPASS_DISCOUNT_INACTIVE"));
		}
		finally {
			jdbc.update("UPDATE core_discount_type SET active = TRUE WHERE code = 'DONGTAN_PASS'");
		}
	}

	@Test
	void 다른_달을_보내도_열린_청구가_있으면_409다() throws Exception {
		Redeemed redeemed = redeemed();
		issue(redeemed.redemptionId(), 8, "").andExpect(status().isCreated());

		mockMvc.perform(post("/api/v1/dongtanpass/redemptions/" + redeemed.redemptionId() + "/invoice")
				.with(csrfToken(mockMvc)).with(idempotencyKey()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"targetMonth\": \"2026-12\", \"lines\": [{\"classId\": " + cls + ", \"lessonCount\": 8}]}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_REDEMPTION_ALREADY_INVOICED"));
	}

	private long studentOf(Redeemed redeemed) {
		return jdbc.queryForObject("SELECT student_id FROM dongtanpass_redemption WHERE id = ?", Long.class,
				redeemed.redemptionId());
	}

	@Test
	void 모르는_칸은_400이다() throws Exception {
		Redeemed redeemed = redeemed();

		mockMvc.perform(post("/api/v1/dongtanpass/redemptions/" + redeemed.redemptionId() + "/invoice")
				.with(csrfToken(mockMvc)).with(idempotencyKey()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"targetMonth\": \"2026-11\", \"lines\": [{\"classId\": " + cls
						+ ", \"lessonCount\": 8}], \"discountRate\": 0.5}"))
			.andExpect(status().isBadRequest());
	}

	private ResultActions issue(long redemptionId, int lessons, String discountCodes) throws Exception {
		return mockMvc.perform(post("/api/v1/dongtanpass/redemptions/" + redemptionId + "/invoice")
			.with(csrfToken(mockMvc)).with(idempotencyKey()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"targetMonth\": \"2026-11\", \"lines\": [{\"classId\": %d, \"lessonCount\": %d}], \"discountCodes\": [%s]}"
				.formatted(cls, lessons, discountCodes)));
	}

	// 실제 흐름: 연계 학원이 발급한 패스 → 동탄관에 학생 등록(같은 이름, 학부모 번호) → 데스크 사용 처리.
	private Redeemed redeemed() {
		String name = MemberFixtures.uniqueName();
		String phone = "0109" + String.format("%07d", ThreadLocalRandom.current().nextInt(10_000_000));
		Pass pass = passes.saveAndFlush(Pass.issue(PARTNER, PassNumber.generate(RANDOM),
				LinkToken.generate(RANDOM).hash(), name, phoneProtector.fullIndex(PhoneNumber.of(phone)),
				clock.instant(), SEOUL, LocalDate.now(clock), Money.of(150_000), PaymentMethod.CARD));
		long student = students.register(MemberFixtures.registration(name,
				MemberFixtures.mother(PhoneNumber.of(phone)))).id();
		long redemptionId = redeemService.redeem(pass.getId(), student, STAFF).redemptionId();
		return new Redeemed(redemptionId, pass.getPassNo());
	}

	private static long idOf(ResultActions result) throws Exception {
		String json = result.andReturn().getResponse().getContentAsString();
		return Long.parseLong(json.replaceAll("(?s).*\"invoiceId\":(\\d+).*", "$1"));
	}

	private record Redeemed(long redemptionId, String passNo) {
	}

}
