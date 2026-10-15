package com.jey.dongtanpass.domain;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.jey.TestcontainersConfiguration;
import com.jey.core.shared.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class ConsentPersistenceTest {

	private static final SecureRandom RANDOM = new SecureRandom();

	@Autowired
	PassRepository passes;

	@Autowired
	ConsentRepository consents;

	@Autowired
	PartnerRepository partners;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void 동의_기록을_패스와_함께_저장한다() {
		Pass pass = newPass();
		Instant agreedAt = Instant.parse("2026-10-14T01:00:00Z");

		consents.saveAndFlush(Consent.thirdPartyProvision(pass.getId(), "2026-10-v1", agreedAt));

		assertThat(jdbc.queryForMap("SELECT consent_type, notice_version, channel FROM dongtanpass_consent WHERE pass_id = ?",
				pass.getId()))
			.containsEntry("consent_type", "THIRD_PARTY_PROVISION")
			.containsEntry("notice_version", "2026-10-v1")
			.containsEntry("channel", "PARTNER_CONFIRMED");
	}

	// 패스 하나에 같은 종류의 동의는 한 번만 남는다.
	@Test
	void 같은_패스에_같은_종류의_동의는_두_번_저장되지_않는다() {
		Pass pass = newPass();
		Instant agreedAt = Instant.parse("2026-10-14T01:00:00Z");
		consents.saveAndFlush(Consent.thirdPartyProvision(pass.getId(), "2026-10-v1", agreedAt));

		assertThatThrownBy(() -> consents.saveAndFlush(Consent.thirdPartyProvision(pass.getId(), "2026-10-v1", agreedAt)))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 고지_버전이나_시각이_없으면_만들지_않는다() {
		assertThatIllegalArgumentException().isThrownBy(() -> Consent.thirdPartyProvision(1L, " ", Instant.EPOCH));
		assertThatIllegalArgumentException().isThrownBy(() -> Consent.thirdPartyProvision(1L, "2026-10-v1", null));
	}

	private Pass newPass() {
		long partnerId = partners
			.saveAndFlush(Partner.create("동의확인-" + UUID.randomUUID().toString().substring(0, 8)))
			.getId();
		return passes.saveAndFlush(Pass.issue(partnerId, PassNumber.generate(RANDOM), LinkToken.generate(RANDOM).hash(),
				"김하윤", "phone-index", Instant.parse("2026-10-14T01:00:00Z"), LocalDate.of(2026, 10, 14),
				LocalDate.of(2026, 10, 14), Money.of(150000), PaymentMethod.CARD));
	}

}
