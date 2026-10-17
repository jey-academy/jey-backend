package com.jey.dongtanpass.domain;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class RedemptionTest {

	@Test
	void 사용_기록은_패스_학생_지점_시각을_가진다() {
		Instant at = Instant.parse("2026-10-17T01:00:00Z");

		Redemption redemption = Redemption.of(1, 2, 3, at);

		assertThat(redemption.getPassId()).isEqualTo(1);
		assertThat(redemption.getStudentId()).isEqualTo(2);
		assertThat(redemption.getCampusId()).isEqualTo(3);
		assertThat(redemption.getRedeemedAt()).isEqualTo(at);
	}

	@Test
	void 사용_시각이_없으면_만들지_않는다() {
		assertThatIllegalArgumentException().isThrownBy(() -> Redemption.of(1, 2, 3, null));
	}

}
