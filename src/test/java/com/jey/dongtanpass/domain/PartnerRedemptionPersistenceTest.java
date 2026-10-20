package com.jey.dongtanpass.domain;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.jey.TestcontainersConfiguration;
import com.jey.core.shared.Money;
import com.jey.core.shared.UniqueKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 연계 학원의 JEY 패스 사용 기록. 패스 하나에 한 줄, 같은 학원에서 같은 학생은 한 줄(다른 학원이면 따로).
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class PartnerRedemptionPersistenceTest {

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private static final Instant AT = Instant.parse("2026-10-20T01:02:03.123456Z");

	@Autowired
	PartnerRedemptionRepository redemptions;

	@Autowired
	PassRepository passes;

	@Autowired
	PartnerRepository partners;

	@Autowired
	TransactionTemplate transaction;

	@Test
	void 저장한_기록을_다시_읽으면_모든_칸이_같다() {
		long partnerId = newPartner();
		long student = newStudentId();
		PartnerRedemption saved = redeem(newCampusPass(student), partnerId, 285555);

		PartnerRedemption read = redemptions.findById(saved.getId()).orElseThrow();
		assertThat(read.getPartnerId()).isEqualTo(partnerId);
		assertThat(read.getStudentId()).isEqualTo(student);
		assertThat(read.getOriginalFee()).isEqualTo(Money.of(285555));
		assertThat(read.getDiscountRate()).isEqualByComparingTo("0.05");
		assertThat(read.getDiscountAmount()).isEqualTo(Money.of(14278));
		assertThat(read.getPayableAmount()).isEqualTo(Money.of(271277));
		assertThat(read.getRedeemedAt()).isEqualTo(AT);
		assertThat(redemptions.findByPassId(read.getPassId())).map(PartnerRedemption::getId).contains(saved.getId());
		assertThat(redemptions.existsByPartnerIdAndStudentId(partnerId, student)).isTrue();
		assertThat(redemptions.existsByPartnerIdAndStudentId(newPartner(), student)).isFalse();
	}

	// 사용 처리가 이 제약 이름으로 "이 학원에서 이미 할인받은 학생"을 가린다.
	@Test
	void 같은_학원에서_같은_학생은_두_번_저장되지_않는다() {
		long partnerId = newPartner();
		long student = newStudentId();
		redeem(newCampusPass(student), partnerId, 280000);

		assertThatThrownBy(() -> redeem(newCampusPass(student), partnerId, 280000))
			.isInstanceOfSatisfying(DataIntegrityViolationException.class, ex -> assertThat(
					UniqueKeys.isViolationOf(ex, "uk_dongtanpass_partner_redemption_student"))
				.isTrue());
	}

	@Test
	void 다른_학원이면_같은_학생도_저장된다() {
		long student = newStudentId();
		redeem(newCampusPass(student), newPartner(), 280000);

		assertThat(redeem(newCampusPass(student), newPartner(), 280000).getId()).isNotNull();
	}

	// 패스를 먼저(사용됨으로), 기록을 나중에 저장한다(데스크와 같은 순서).
	private PartnerRedemption redeem(long passId, long partnerId, long fee) {
		return transaction.execute(status -> {
			Pass pass = passes.findById(passId).orElseThrow();
			PartnerRedemption redemption = pass.redeemAtPartner(partnerId, Money.of(fee), AT);
			passes.saveAndFlush(pass);
			return redemptions.saveAndFlush(redemption);
		});
	}

	private long newCampusPass(long student) {
		return passes.saveAndFlush(Pass.issueAtCampus(1L, student, PassNumber.generate(RANDOM),
				LinkToken.generate(RANDOM).hash(), "김하윤", "phone-index", AT, SEOUL, LocalDate.of(2026, 10, 20),
				Money.of(280000), PaymentMethod.CARD, new BigDecimal("0.05")))
			.getId();
	}

	private long newPartner() {
		return partners.saveAndFlush(Partner.create("연계사용-" + UUID.randomUUID().toString().substring(0, 8))).getId();
	}

	private static long newStudentId() {
		return ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE / 2);
	}

}
