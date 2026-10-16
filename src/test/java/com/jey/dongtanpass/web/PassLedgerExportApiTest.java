package com.jey.dongtanpass.web;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.Money;
import com.jey.dongtanpass.domain.LinkToken;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassNumber;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static com.jey.dongtanpass.DongtanpassFixtures.upsertPartner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 연계 학원은 발급 장부를 CSV로 내려받는다. 장부와 같은 학원·기간 조건이다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PassLedgerExportApiTest {

	private static final String EXPORT = "/api/v1/dongtanpass/passes/export";

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private static final long PARTNER = 900_601L;

	private static final long OTHER = 900_602L;

	private static final long INACTIVE = 900_603L;

	private static final SecureRandom RANDOM = new SecureRandom();

	@Autowired
	MockMvc mockMvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PassRepository passes;

	@BeforeEach
	void setUp() {
		upsertPartner(jdbc, PARTNER, true);
		upsertPartner(jdbc, OTHER, true);
		upsertPartner(jdbc, INACTIVE, false);
		jdbc.update("DELETE c FROM dongtanpass_consent c JOIN dongtanpass_pass p ON p.id = c.pass_id WHERE p.partner_id IN (?, ?)",
				PARTNER, OTHER);
		jdbc.update("DELETE FROM dongtanpass_pass WHERE partner_id IN (?, ?)", PARTNER, OTHER);
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = PARTNER)
	void 자기_학원_패스를_최신_발급_순으로_BOM과_머리줄과_함께_내려준다() throws Exception {
		Pass older = save(PARTNER, "김하윤", LocalDate.of(2026, 10, 1));
		Pass newer = save(PARTNER, "이서준", LocalDate.of(2026, 10, 2));
		save(OTHER, "박다른", LocalDate.of(2026, 10, 2));

		byte[] file = mockMvc.perform(get(EXPORT))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", startsWith("text/csv")))
			.andExpect(header().string("Content-Disposition", containsString("attachment")))
			.andExpect(header().string("Content-Disposition", containsString(".csv")))
			.andReturn()
			.getResponse()
			.getContentAsByteArray();

		assertThat(Arrays.copyOf(file, 3)).containsExactly(0xEF, 0xBB, 0xBF);
		String[] lines = new String(file, 3, file.length - 3, StandardCharsets.UTF_8).split("\r\n");
		assertThat(lines).hasSize(3);
		assertThat(lines[0]).startsWith("\"패스 번호\"");
		assertThat(lines[1]).startsWith("\"" + newer.getPassNo() + "\",\"이서준\",\"2026-10-02\"");
		assertThat(lines[2]).startsWith("\"" + older.getPassNo() + "\",\"김하윤\"");
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = PARTNER)
	void 기간으로_자른다() throws Exception {
		save(PARTNER, "구월", LocalDate.of(2026, 9, 30));
		save(PARTNER, "시월", LocalDate.of(2026, 10, 1));

		String text = csv(EXPORT + "?from=2026-10-01&to=2026-10-31");

		assertThat(text).contains("\"시월\"").doesNotContain("\"구월\"");
	}

	// 한국 0시에 발급한 패스의 발급일은 그날이다(UTC로는 전날 15시).
	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = PARTNER)
	void 발급일은_한국_날짜로_쓴다() throws Exception {
		Instant issuedAt = Instant.parse("2026-10-14T15:00:00Z");
		passes.saveAndFlush(Pass.issue(PARTNER, PassNumber.generate(RANDOM), LinkToken.generate(RANDOM).hash(), "자정발급",
				"phone-index", issuedAt, SEOUL, LocalDate.of(2026, 10, 15), Money.of(150000), PaymentMethod.CARD));

		assertThat(csv(EXPORT)).contains("\"자정발급\",\"2026-10-15\",\"2026-11-15\"");
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = PARTNER)
	void 수식으로_시작하는_이름은_글자로_나간다() throws Exception {
		save(PARTNER, "=1+1", LocalDate.of(2026, 10, 1));

		assertThat(csv(EXPORT)).contains("\"'=1+1\"");
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = PARTNER)
	void 시작일이_종료일보다_뒤면_400이다() throws Exception {
		mockMvc.perform(get(EXPORT).param("from", "2026-10-16").param("to", "2026-10-15"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_INVALID_PERIOD"));
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = PARTNER)
	void 받는_범위_밖의_날짜는_400이다() throws Exception {
		mockMvc.perform(get(EXPORT).param("to", "+999999999-12-31"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_INVALID_PERIOD"));
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = INACTIVE)
	void 꺼진_학원은_내려받을_수_없다() throws Exception {
		mockMvc.perform(get(EXPORT))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("DONGTANPASS_PARTNER_INACTIVE"));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1)
	void 직원은_내려받을_수_없다() throws Exception {
		mockMvc.perform(get(EXPORT)).andExpect(status().isForbidden());
	}

	private String csv(String url) throws Exception {
		byte[] file = mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
		return new String(file, 3, file.length - 3, StandardCharsets.UTF_8);
	}

	private Pass save(long partnerId, String name, LocalDate issuedOn) {
		Instant issuedAt = issuedOn.atTime(10, 0).atZone(SEOUL).toInstant();
		return passes.saveAndFlush(Pass.issue(partnerId, PassNumber.generate(RANDOM), LinkToken.generate(RANDOM).hash(),
				name, "phone-index", issuedAt, SEOUL, issuedOn, Money.of(150000), PaymentMethod.CARD));
	}

}
