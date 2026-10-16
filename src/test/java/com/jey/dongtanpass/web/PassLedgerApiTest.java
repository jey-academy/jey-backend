package com.jey.dongtanpass.web;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.Money;
import com.jey.core.shared.PageLimits;
import com.jey.dongtanpass.domain.LinkToken;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassNumber;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static com.jey.dongtanpass.DongtanpassFixtures.upsertPartner;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 연계 학원은 자기 학원이 발급한 패스를 장부로 본다. 학부모 번호는 장부에 없다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PassLedgerApiTest {

	private static final String LEDGER = "/api/v1/dongtanpass/passes";

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private static final long PARTNER = 900_501L;

	private static final long OTHER = 900_502L;

	private static final long INACTIVE = 900_503L;

	private static final SecureRandom RANDOM = new SecureRandom();

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PassRepository passes;

	@Autowired
	Clock clock;

	@BeforeEach
	void setUp() {
		upsertPartner(jdbc, PARTNER, true);
		upsertPartner(jdbc, OTHER, true);
		upsertPartner(jdbc, INACTIVE, false);
		// 장부는 학원별이다. 이 클래스의 학원이 남긴 패스만 지운다.
		jdbc.update("DELETE c FROM dongtanpass_consent c JOIN dongtanpass_pass p ON p.id = c.pass_id WHERE p.partner_id IN (?, ?)",
				PARTNER, OTHER);
		jdbc.update("DELETE FROM dongtanpass_pass WHERE partner_id IN (?, ?)", PARTNER, OTHER);
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = PARTNER)
	void 자기_학원_패스만_최신_발급_순으로_나오고_학부모_번호는_없다() throws Exception {
		Pass older = save(PARTNER, "김하윤", today().minusDays(2));
		Pass newer = save(PARTNER, "이서준", today());
		save(OTHER, "박다른", today());

		mockMvc.perform(get(LEDGER))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].id").value(contains(newer.getId().intValue(), older.getId().intValue())))
			.andExpect(jsonPath("$.totalElements").value(2))
			.andExpect(jsonPath("$.page").value(0))
			.andExpect(jsonPath("$.size").value(20))
			.andExpect(jsonPath("$.items[0].passNo").value(newer.getPassNo()))
			.andExpect(jsonPath("$.items[0].studentName").value("이서준"))
			.andExpect(jsonPath("$.items[0].issuedOn").value(today().toString()))
			.andExpect(jsonPath("$.items[0].expiresOn").value(today().plusMonths(1).toString()))
			.andExpect(jsonPath("$.items[0].state").value("ISSUED"))
			.andExpect(jsonPath("$.items[0].paidOn").value(today().toString()))
			.andExpect(jsonPath("$.items[0].paidAmount").value(150000))
			.andExpect(jsonPath("$.items[0].paymentMethod").value("CARD"))
			.andExpect(content().string(not(containsString("guardian"))))
			.andExpect(content().string(not(containsString("phone-index"))))
			.andExpect(content().string(not(containsString("박다른"))));
	}

	// 발급일은 한국 날짜다. UTC로 자르면 9시간 어긋난다.
	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = PARTNER)
	void 기간은_한국_날짜의_발급일로_자르고_양끝을_포함한다() throws Exception {
		Pass lastOf14 = saveAt(PARTNER, Instant.parse("2026-10-14T14:59:59.999999Z"));
		Pass firstOf15 = saveAt(PARTNER, Instant.parse("2026-10-14T15:00:00Z"));
		Pass lastOf16 = saveAt(PARTNER, Instant.parse("2026-10-16T14:59:59Z"));
		saveAt(PARTNER, Instant.parse("2026-10-16T15:00:00Z"));

		mockMvc.perform(get(LEDGER).param("from", "2026-10-15").param("to", "2026-10-16"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].id").value(contains(lastOf16.getId().intValue(), firstOf15.getId().intValue())));
		mockMvc.perform(get(LEDGER).param("to", "2026-10-14"))
			.andExpect(jsonPath("$.items[*].id").value(contains(lastOf14.getId().intValue())));
		mockMvc.perform(get(LEDGER).param("from", "2026-10-15"))
			.andExpect(jsonPath("$.totalElements").value(3));
	}

	// 거르기와 따로, 줄에 찍히는 발급일도 한국 날짜다. UTC로 바꾸면 한국 0~9시 발급이 전날로 보인다.
	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = PARTNER)
	void 줄의_발급일은_한국_날짜다() throws Exception {
		Pass lastOf14 = saveAt(PARTNER, Instant.parse("2026-10-14T14:59:59.999999Z"));
		Pass firstOf15 = saveAt(PARTNER, Instant.parse("2026-10-14T15:00:00Z"));

		mockMvc.perform(get(LEDGER).param("from", "2026-10-14").param("to", "2026-10-15"))
			.andExpect(jsonPath("$.items[?(@.id == " + lastOf14.getId() + ")].issuedOn").value(contains("2026-10-14")))
			.andExpect(jsonPath("$.items[?(@.id == " + firstOf15.getId() + ")].issuedOn").value(contains("2026-10-15")))
			.andExpect(jsonPath("$.items[?(@.id == " + firstOf15.getId() + ")].expiresOn").value(contains("2026-11-15")));
	}

	// 만료일은 오늘을 기준으로 직접 정한다(달마다 날 수가 달라 발급일로 맞추면 날짜에 따라 깨진다).
	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = PARTNER)
	void 보이는_상태로_거른다() throws Exception {
		Pass usable = saveExpiringOn("쓸수있음", today().plusDays(10), 1);
		Pass expiresToday = saveExpiringOn("오늘만료", today(), 2);
		Pass expired = saveExpiringOn("만료됨", today().minusDays(1), 3);
		Pass canceled = saveExpiringOn("취소됨", today().plusDays(10), 4);
		jdbc.update("UPDATE dongtanpass_pass SET status = 'CANCELED' WHERE id = ?", canceled.getId());
		Pass redeemed = saveExpiringOn("사용됨", today().minusDays(5), 5);
		jdbc.update("UPDATE dongtanpass_pass SET status = 'REDEEMED' WHERE id = ?", redeemed.getId());

		mockMvc.perform(get(LEDGER).param("status", "ISSUED"))
			.andExpect(jsonPath("$.items[*].id").value(contains(expiresToday.getId().intValue(), usable.getId().intValue())))
			.andExpect(jsonPath("$.items[*].state").value(contains("ISSUED", "ISSUED")));
		mockMvc.perform(get(LEDGER).param("status", "EXPIRED"))
			.andExpect(jsonPath("$.items[*].id").value(contains(expired.getId().intValue())))
			.andExpect(jsonPath("$.items[0].state").value("EXPIRED"));
		mockMvc.perform(get(LEDGER).param("status", "CANCELED"))
			.andExpect(jsonPath("$.items[*].id").value(contains(canceled.getId().intValue())))
			.andExpect(jsonPath("$.items[0].state").value("CANCELED"));
		// 사용된 패스는 기간이 지났어도 사용됨이다
		mockMvc.perform(get(LEDGER).param("status", "REDEEMED"))
			.andExpect(jsonPath("$.items[*].id").value(contains(redeemed.getId().intValue())))
			.andExpect(jsonPath("$.items[0].state").value("REDEEMED"));
		mockMvc.perform(get(LEDGER)).andExpect(jsonPath("$.totalElements").value(5));
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = PARTNER)
	void 나눠서_준다() throws Exception {
		Pass first = save(PARTNER, "첫째", today().minusDays(2));
		save(PARTNER, "둘째", today().minusDays(1));
		save(PARTNER, "셋째", today());

		mockMvc.perform(get(LEDGER).param("page", "1").param("size", "2"))
			.andExpect(jsonPath("$.items[*].id").value(contains(first.getId().intValue())))
			.andExpect(jsonPath("$.page").value(1))
			.andExpect(jsonPath("$.size").value(2))
			.andExpect(jsonPath("$.totalElements").value(3))
			.andExpect(jsonPath("$.totalPages").value(2));
	}

	// 화면의 실수가 빈 장부로 보이지 않게 한다.
	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = PARTNER)
	void 시작일이_종료일보다_뒤면_400이다() throws Exception {
		mockMvc.perform(get(LEDGER).param("from", "2026-10-16").param("to", "2026-10-15"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_INVALID_PERIOD"));
	}

	// 아주 먼 날짜는 하루를 더하다 넘치거나 DB의 시각 범위를 벗어난다(500). 받는 범위는 2000-01-01~2099-12-31이다.
	@ParameterizedTest
	@CsvSource({ "to,+999999999-12-31", "to,9999-12-31", "to,2100-01-01", "from,1999-12-31", "from,-999999999-01-01" })
	@WithJeyUser(role = UserRole.PARTNER, partnerId = PARTNER)
	void 받는_범위_밖의_날짜는_400이다(String param, String date) throws Exception {
		mockMvc.perform(get(LEDGER).param(param, date))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_INVALID_PERIOD"));
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = PARTNER)
	void 받는_범위의_양끝은_된다() throws Exception {
		mockMvc.perform(get(LEDGER).param("from", "2000-01-01").param("to", "2099-12-31")).andExpect(status().isOk());
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = PARTNER)
	void 쪽_번호와_크기가_범위를_넘으면_400이다() throws Exception {
		mockMvc.perform(get(LEDGER).param("page", String.valueOf(PageLimits.MAX_PAGE + 1)))
			.andExpect(status().isBadRequest());
		mockMvc.perform(get(LEDGER).param("size", "101")).andExpect(status().isBadRequest());
		mockMvc.perform(get(LEDGER).param("size", "0")).andExpect(status().isBadRequest());
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = INACTIVE)
	void 꺼진_학원은_장부를_볼_수_없다() throws Exception {
		mockMvc.perform(get(LEDGER))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PARTNER_INACTIVE"));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1)
	void 직원은_장부를_볼_수_없다() throws Exception {
		mockMvc.perform(get(LEDGER)).andExpect(status().isForbidden());
	}

	private Pass save(long partnerId, String name, LocalDate issuedOn) {
		Instant issuedAt = issuedOn.atTime(10, 0).atZone(SEOUL).toInstant();
		return passes.saveAndFlush(Pass.issue(partnerId, PassNumber.generate(RANDOM), LinkToken.generate(RANDOM).hash(),
				name, "phone-index", issuedAt, SEOUL, issuedOn, Money.of(150000), PaymentMethod.CARD));
	}

	private Pass saveAt(long partnerId, Instant issuedAt) {
		LocalDate issuedOn = issuedAt.atZone(SEOUL).toLocalDate();
		return passes.saveAndFlush(Pass.issue(partnerId, PassNumber.generate(RANDOM), LinkToken.generate(RANDOM).hash(),
				"김하윤", "phone-index", issuedAt, SEOUL, issuedOn, Money.of(150000), PaymentMethod.CARD));
	}

	// 발급 시각은 순서대로(분 단위로 늦게) 두고 만료일만 정한다.
	private Pass saveExpiringOn(String name, LocalDate expiresOn, int minute) {
		Instant issuedAt = today().atTime(9, minute).atZone(SEOUL).toInstant();
		Pass pass = passes.saveAndFlush(Pass.issue(PARTNER, PassNumber.generate(RANDOM), LinkToken.generate(RANDOM).hash(),
				name, "phone-index", issuedAt, SEOUL, today(), Money.of(150000), PaymentMethod.CARD));
		jdbc.update("UPDATE dongtanpass_pass SET expires_on = ? WHERE id = ?", expiresOn, pass.getId());
		return pass;
	}

	private LocalDate today() {
		return LocalDate.now(clock);
	}

}
