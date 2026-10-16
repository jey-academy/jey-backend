package com.jey.dongtanpass.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.jey.core.shared.Money;
import com.jey.dongtanpass.application.LedgerRow;
import com.jey.dongtanpass.domain.PassState;
import com.jey.dongtanpass.domain.PaymentMethod;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerPageResponseTest {

	// 응답을 쓰는 쪽(DEBUG 로그)이 toString을 찍어도 학생 이름은 남지 않는다.
	@Test
	void 문자열로_바꿔도_학생_이름은_없다() {
		LedgerRow row = new LedgerRow(7L, "DT-ABC234", "김하윤", Instant.parse("2026-10-15T01:00:00Z"),
				LocalDate.of(2026, 10, 15), LocalDate.of(2026, 11, 15), PassState.ISSUED, LocalDate.of(2026, 10, 15),
				Money.of(150000), PaymentMethod.CARD);

		LedgerPageResponse response = LedgerPageResponse.from(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1));

		assertThat(response.toString()).doesNotContain("김하윤").contains("DT-ABC234");
		assertThat(response.items().getFirst().toString()).doesNotContain("김하윤").contains("id=7");
	}

}
