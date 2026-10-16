package com.jey.dongtanpass.domain;

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
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class PassPersistenceTest {

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	@Autowired
	PassRepository passes;

	@Autowired
	PartnerRepository partners;

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

	private Pass newPass(String passNo) {
		return Pass.issue(partnerId, passNo, LinkToken.generate(RANDOM).hash(), "김하윤", "phone-index",
				Instant.parse("2026-10-14T01:00:00Z"), SEOUL, LocalDate.of(2026, 10, 14),
				Money.of(150000), PaymentMethod.CARD);
	}

}
