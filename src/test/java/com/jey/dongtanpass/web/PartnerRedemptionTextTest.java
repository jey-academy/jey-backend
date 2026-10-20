package com.jey.dongtanpass.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.jey.core.shared.Money;
import com.jey.core.shared.PhoneNumber;
import com.jey.dongtanpass.application.PartnerRedemptionRow;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;

// 연계 학원 쪽 요청·응답을 로그에 남기는 곳이 생겨도 학생 이름과 학부모 번호가 남지 않아야 한다.
class PartnerRedemptionTextTest {

	@Test
	void 사용_처리_요청을_문자열로_바꾸면_이름과_번호를_가린다() {
		String text = new PartnerRedeemRequest("김하윤", List.of(PhoneNumber.of("01098765432")), Money.of(280000))
			.toString();

		assertThat(text).doesNotContain("김하윤").doesNotContain("01098765432").doesNotContain("98765432");
	}

	@Test
	void 사용_내역을_문자열로_바꿔도_학생_이름은_없다() {
		PartnerRedemptionRow row = new PartnerRedemptionRow(7L, Instant.parse("2026-10-20T01:00:00Z"),
				LocalDate.of(2026, 10, 20), 3L, "테스트학원", 9L, "DT-ABC234", "김하윤", Money.of(280000),
				new BigDecimal("0.05"), Money.of(14000), Money.of(266000));

		PartnerRedemptionPageResponse response = PartnerRedemptionPageResponse
			.from(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1));

		assertThat(row.toString()).doesNotContain("김하윤").contains("id=7");
		assertThat(response.toString()).doesNotContain("김하윤");
		assertThat(response.items().getFirst().toString()).doesNotContain("김하윤").contains("id=7");
	}

}
