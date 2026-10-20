package com.jey.dongtanpass.web;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ThreadLocalRandom;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.Money;
import com.jey.dongtanpass.domain.LinkToken;
import com.jey.dongtanpass.domain.Partner;
import com.jey.dongtanpass.domain.PartnerRepository;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassNumber;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.PaymentMethod;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 관리자가 JEY가 발급한 패스를 본다. 연계 학원이 발급한 패스는 섞이지 않는다.
// 같은 DB에 다른 시험의 패스가 쌓이므로, 학생 ID(시험마다 새 값)나 먼 날짜로 범위를 좁혀 본다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CampusPassLedgerApiTest {

	private static final String PASSES = "/api/v1/dongtanpass/campus-passes";

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final long PROVIDER = 1L;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	PassRepository passes;

	@Autowired
	PartnerRepository partners;

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 학생으로_거르면_그_학생의_패스가_최신_발급_순으로_보인다() throws Exception {
		long student = newStudentId();
		Pass older = saveCampusPass(student, Instant.parse("2026-10-01T01:00:00Z"));
		Pass newer = saveCampusPass(student, Instant.parse("2026-10-15T01:00:00Z"));

		mockMvc.perform(get(PASSES).param("studentId", String.valueOf(student)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(2))
			.andExpect(jsonPath("$.items[*].id").value(contains(newer.getId().intValue(), older.getId().intValue())))
			.andExpect(jsonPath("$.items[0].studentId").value(student))
			.andExpect(jsonPath("$.items[0].studentName").value("김하윤"))
			.andExpect(jsonPath("$.items[0].discountRate").value(0.05))
			.andExpect(jsonPath("$.items[0].paidAmount").value(280000))
			.andExpect(jsonPath("$.items[0].paymentMethod").value("CARD"))
			.andExpect(jsonPath("$.items[0].issuedOn").value("2026-10-15"));
	}

	// 연계 학원 발급분은 학생 ID가 없어 학생 조건에는 원래 걸리지 않는다. 날짜로만 좁혀 섞이지 않는지 본다.
	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 연계_학원이_발급한_패스는_보이지_않는다() throws Exception {
		Instant farDay = Instant.parse("2031-03-03T01:00:00Z");
		long partnerId = partners.saveAndFlush(Partner.create("JEY목록-" + newStudentId())).getId();
		passes.saveAndFlush(Pass.issue(partnerId, PassNumber.generate(RANDOM), LinkToken.generate(RANDOM).hash(),
				"이연계", "phone-index", farDay, SEOUL, LocalDate.of(2031, 3, 3), Money.of(150000), PaymentMethod.CARD));
		Pass campus = saveCampusPass(newStudentId(), farDay);

		mockMvc.perform(get(PASSES).param("from", "2031-03-03").param("to", "2031-03-03"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].id").value(contains(campus.getId().intValue())));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 상태로_거른다() throws Exception {
		long student = newStudentId();
		saveCampusPass(student, Instant.parse("2026-01-05T01:00:00Z"));
		Pass fresh = saveCampusPass(student, Instant.now().minusSeconds(60));

		mockMvc.perform(get(PASSES).param("studentId", String.valueOf(student)).param("status", "EXPIRED"))
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].state").value("EXPIRED"));
		mockMvc.perform(get(PASSES).param("studentId", String.valueOf(student)).param("status", "ISSUED"))
			.andExpect(jsonPath("$.items[*].id").value(contains(fresh.getId().intValue())));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 잘못된_기간은_400() throws Exception {
		mockMvc.perform(get(PASSES).param("from", "2026-10-20").param("to", "2026-10-01"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_INVALID_PERIOD"));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = PROVIDER)
	void 직원은_보지_못한다() throws Exception {
		mockMvc.perform(get(PASSES)).andExpect(status().isForbidden());
	}

	private Pass saveCampusPass(long student, Instant issuedAt) {
		return passes.saveAndFlush(Pass.issueAtCampus(PROVIDER, student, PassNumber.generate(RANDOM),
				LinkToken.generate(RANDOM).hash(), "김하윤", "phone-index", issuedAt, SEOUL,
				LocalDate.ofInstant(issuedAt, SEOUL), Money.of(280000), PaymentMethod.CARD, new BigDecimal("0.05")));
	}

	// 학생 자료와 잇지 않는 시험이라 아무 ID나 쓴다(외래 키가 없다). 다른 시험과 겹치지 않게 크게 고른다.
	private static long newStudentId() {
		return ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE / 2);
	}

}
