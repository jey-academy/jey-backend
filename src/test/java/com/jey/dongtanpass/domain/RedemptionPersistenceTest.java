package com.jey.dongtanpass.domain;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.jey.TestcontainersConfiguration;
import com.jey.core.shared.Money;
import com.jey.core.shared.UniqueKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 학생 ID는 다른 모듈의 자료라 외래 키가 없다. 테스트마다 다른 큰 번호를 쓴다(다른 테스트의 사용 기록과 겹치지 않게).
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class RedemptionPersistenceTest {

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	@Autowired
	RedemptionRepository redemptions;

	@Autowired
	PassRepository passes;

	@Autowired
	PartnerRepository partners;

	@Autowired
	JdbcTemplate jdbc;

	private long partnerId;

	@BeforeEach
	void setUp() {
		partnerId = partners.saveAndFlush(Partner.create("사용기록-" + UUID.randomUUID().toString().substring(0, 8)))
			.getId();
	}

	// 시각은 마이크로초까지 남는다. DB에는 한국 시각으로 적힌다(ADR-0017).
	@Test
	void 저장한_사용_기록을_다시_읽으면_모든_칸이_같다() {
		long passId = newPass();
		long studentId = newStudentId();
		Instant at = Instant.parse("2026-10-17T01:02:03.123456Z");

		Redemption saved = redemptions.saveAndFlush(Redemption.of(passId, studentId, 1, at));

		Redemption read = redemptions.findById(saved.getId()).orElseThrow();
		assertThat(read.getPassId()).isEqualTo(passId);
		assertThat(read.getStudentId()).isEqualTo(studentId);
		assertThat(read.getCampusId()).isEqualTo(1);
		assertThat(read.getRedeemedAt()).isEqualTo(at);
		assertThat(jdbc.queryForObject("SELECT CAST(redeemed_at AS CHAR) FROM dongtanpass_redemption WHERE id = ?",
				String.class, saved.getId()))
			.isEqualTo("2026-10-17 10:02:03.123456");
		assertThat(redemptions.findByPassId(passId)).map(Redemption::getId).contains(saved.getId());
		assertThat(redemptions.existsByStudentId(studentId)).isTrue();
		assertThat(redemptions.existsByStudentId(newStudentId())).isFalse();
	}

	// 사용 처리가 이 제약 이름으로 "이미 사용된 패스"와 "이미 패스를 쓴 학생"을 가린다.
	@Test
	void 한_패스와_한_학생은_한_번만_쓰이고_제약_이름으로_가릴_수_있다() {
		long passId = newPass();
		long studentId = newStudentId();
		Instant at = Instant.parse("2026-10-17T01:00:00Z");
		redemptions.saveAndFlush(Redemption.of(passId, studentId, 1, at));

		assertThatThrownBy(() -> redemptions.saveAndFlush(Redemption.of(passId, newStudentId(), 1, at)))
			.isInstanceOfSatisfying(DataIntegrityViolationException.class, ex -> {
				assertThat(UniqueKeys.isViolationOf(ex, "uk_dongtanpass_redemption_pass")).isTrue();
				assertThat(UniqueKeys.isViolationOf(ex, "uk_dongtanpass_redemption_student")).isFalse();
			});
		assertThatThrownBy(() -> redemptions.saveAndFlush(Redemption.of(newPass(), studentId, 1, at)))
			.isInstanceOfSatisfying(DataIntegrityViolationException.class, ex -> {
				assertThat(UniqueKeys.isViolationOf(ex, "uk_dongtanpass_redemption_student")).isTrue();
				assertThat(UniqueKeys.isViolationOf(ex, "uk_dongtanpass_redemption_pass")).isFalse();
			});
	}

	@Test
	void 없는_패스의_사용_기록은_저장되지_않는다() {
		assertThatThrownBy(() -> redemptions
			.saveAndFlush(Redemption.of(Long.MAX_VALUE, newStudentId(), 1, Instant.parse("2026-10-17T01:00:00Z"))))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	private long newPass() {
		return passes
			.saveAndFlush(Pass.issue(partnerId, PassNumber.generate(RANDOM), LinkToken.generate(RANDOM).hash(), "김하윤",
					"phone-index", Instant.parse("2026-10-14T01:00:00Z"), SEOUL, LocalDate.of(2026, 10, 14),
					Money.of(150000), PaymentMethod.CARD))
			.getId();
	}

	private static long newStudentId() {
		return ThreadLocalRandom.current().nextLong(9_000_000_000L, 9_900_000_000L);
	}

}
