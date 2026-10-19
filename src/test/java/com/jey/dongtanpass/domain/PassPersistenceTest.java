package com.jey.dongtanpass.domain;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

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

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class PassPersistenceTest {

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private static final String DIRECTION_CHECK = "ck_dongtanpass_pass_direction";

	@Autowired
	PassRepository passes;

	@Autowired
	PartnerRepository partners;

	@Autowired
	JdbcTemplate jdbc;

	private long partnerId;

	@BeforeEach
	void setUp() {
		partnerId = partners.saveAndFlush(Partner.create("저장확인-" + UUID.randomUUID().toString().substring(0, 8)))
			.getId();
	}

	@Test
	void 저장한_패스를_다시_읽으면_모든_칸이_같다() {
		Instant issuedAt = Instant.parse("2026-10-14T01:00:00.123456Z");
		Pass saved = passes.saveAndFlush(Pass.issue(partnerId, PassNumber.generate(RANDOM),
				LinkToken.generate(RANDOM).hash(), "김하윤", "phone-index", issuedAt, SEOUL,
				LocalDate.of(2026, 10, 13), Money.of(150000), PaymentMethod.TRANSFER));

		Pass read = passes.findById(saved.getId()).orElseThrow();

		assertThat(read.getPassNo()).isEqualTo(saved.getPassNo());
		assertThat(read.getLinkTokenHash()).isEqualTo(saved.getLinkTokenHash());
		assertThat(read.getPartnerId()).isEqualTo(partnerId);
		assertThat(read.getStudentName()).isEqualTo("김하윤");
		assertThat(read.getGuardianPhoneIndex()).isEqualTo("phone-index");
		assertThat(read.getIssuedAt()).isEqualTo(issuedAt.truncatedTo(ChronoUnit.MICROS));
		assertThat(read.getExpiresOn()).isEqualTo(LocalDate.of(2026, 11, 14));
		assertThat(read.getStatus()).isEqualTo(PassStatus.ISSUED);
		assertThat(read.getPaidOn()).isEqualTo(LocalDate.of(2026, 10, 13));
		assertThat(read.getPaidAmount()).isEqualTo(Money.of(150000));
		assertThat(read.getPaymentMethod()).isEqualTo(PaymentMethod.TRANSFER);
		assertThat(read.getCreatedAt()).isNotNull();
	}

	// 발급은 이 제약 이름으로 번호 겹침을 가려 다시 만든다.
	@Test
	void 같은_패스_번호는_두_번_저장되지_않고_제약_이름으로_가릴_수_있다() {
		String passNo = PassNumber.generate(RANDOM);
		passes.saveAndFlush(newPass(passNo));

		assertThatThrownBy(() -> passes.saveAndFlush(newPass(passNo)))
			.isInstanceOfSatisfying(DataIntegrityViolationException.class,
					ex -> assertThat(UniqueKeys.isViolationOf(ex, "uk_dongtanpass_pass_no")).isTrue());
	}

	@Test
	void 이_학원의_패스만_찾는다() {
		Pass pass = passes.saveAndFlush(newPass(PassNumber.generate(RANDOM)));

		assertThat(passes.findByIdAndPartnerId(pass.getId(), partnerId)).isPresent();
		assertThat(passes.findByIdAndPartnerId(pass.getId(), partnerId + 1_000_000)).isEmpty();
	}

	@Test
	void JEY_발급_패스를_저장하고_다시_읽으면_방향과_JEY_칸이_같다() {
		Pass saved = passes.saveAndFlush(Pass.issueAtCampus(1L, 4242L, PassNumber.generate(RANDOM),
				LinkToken.generate(RANDOM).hash(), "김하윤", "phone-index", Instant.parse("2026-10-14T01:00:00Z"), SEOUL,
				LocalDate.of(2026, 10, 13), Money.of(280000), PaymentMethod.CARD, new BigDecimal("0.0725")));

		Pass read = passes.findById(saved.getId()).orElseThrow();

		assertThat(read.getDirection()).isEqualTo(PassDirection.CAMPUS_ISSUED);
		assertThat(read.getPartnerId()).isNull();
		assertThat(read.getIssuerCampusId()).isEqualTo(1L);
		assertThat(read.getStudentId()).isEqualTo(4242L);
		assertThat(read.getDiscountRate()).isEqualByComparingTo("0.0725");
	}

	// 방향과 칸의 조합은 DB도 막는다(엔티티를 거치지 않고 넣어도). MySQL은 CHECK 위반(3819)을 무결성 오류로 분류하지 않아
	// 스프링이 분류하지 못한 예외가 된다. 코드가 이 오류를 다루지 않으므로(안전망) 제약 이름으로만 본다.
	@Test
	void 방향과_맞지_않는_칸의_조합은_DB가_거부한다() {
		assertThatThrownBy(() -> insertRaw("CAMPUS_ISSUED", partnerId, 1L, 4242L, new BigDecimal("0.05")))
			.hasMessageContaining(DIRECTION_CHECK);
		assertThatThrownBy(() -> insertRaw("PARTNER_ISSUED", partnerId, 1L, null, null))
			.hasMessageContaining(DIRECTION_CHECK);
		assertThatThrownBy(() -> insertRaw("CAMPUS_ISSUED", null, 1L, 4242L, null))
			.hasMessageContaining(DIRECTION_CHECK);
		assertThatThrownBy(() -> insertRaw("CAMPUS_ISSUED", null, 1L, null, new BigDecimal("0.05")))
			.hasMessageContaining(DIRECTION_CHECK);
	}

	@Test
	void 방향에_맞는_칸의_조합은_DB가_받는다() {
		insertRaw("CAMPUS_ISSUED", null, 1L, 4242L, new BigDecimal("0.05"));
		insertRaw("PARTNER_ISSUED", partnerId, null, null, null);
	}

	// 배포 중에는 옛 슬롯이 새 칸을 모른 채 같은 DB에 패스를 넣는다. 그 행은 연계 학원 발급으로 읽혀야 한다.
	@Test
	void 새_칸_없이_넣은_행은_연계_학원_발급으로_읽힌다() {
		String passNo = PassNumber.generate(RANDOM);
		jdbc.update("""
				insert into dongtanpass_pass (pass_no, link_token_hash, partner_id, student_name, guardian_phone_bidx,
				  issued_at, expires_on, status, paid_on, paid_amount, payment_method, version, created_at, updated_at)
				values (?, ?, ?, '김하윤', 'phone-index', '2026-10-14 10:00:00', '2026-11-14', 'ISSUED', '2026-10-14',
				  150000, 'CARD', 0, now(6), now(6))
				""", passNo, LinkToken.generate(RANDOM).hash(), partnerId);

		Pass read = passes.findByPassNo(passNo).orElseThrow();

		assertThat(read.getDirection()).isEqualTo(PassDirection.PARTNER_ISSUED);
		assertThat(read.getPartnerId()).isEqualTo(partnerId);
	}

	private void insertRaw(String direction, Long partner, Long campus, Long student, BigDecimal rate) {
		jdbc.update("""
				insert into dongtanpass_pass (pass_no, direction, link_token_hash, partner_id, issuer_campus_id, student_id,
				  student_name, guardian_phone_bidx, issued_at, expires_on, status, paid_on, paid_amount, payment_method,
				  discount_rate, version, created_at, updated_at)
				values (?, ?, ?, ?, ?, ?, '김하윤', 'phone-index', '2026-10-14 10:00:00', '2026-11-14', 'ISSUED',
				  '2026-10-14', 150000, 'CARD', ?, 0, now(6), now(6))
				""", PassNumber.generate(RANDOM), direction, LinkToken.generate(RANDOM).hash(), partner, campus, student,
				rate);
	}

	private Pass newPass(String passNo) {
		return Pass.issue(partnerId, passNo, LinkToken.generate(RANDOM).hash(), "김하윤", "phone-index",
				Instant.parse("2026-10-14T01:00:00Z"), SEOUL, LocalDate.of(2026, 10, 14),
				Money.of(150000), PaymentMethod.CARD);
	}

}
