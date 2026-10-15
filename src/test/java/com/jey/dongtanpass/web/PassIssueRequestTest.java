package com.jey.dongtanpass.web;

import java.time.LocalDate;

import com.jey.core.shared.Money;
import com.jey.core.shared.PhoneNumber;
import com.jey.dongtanpass.domain.PaymentMethod;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PassIssueRequestTest {

	// 요청이 로그에 찍혀도 학생 이름과 학부모 번호는 남지 않는다.
	@Test
	void 문자열로_바꿔도_이름과_번호는_없다() {
		PassIssueRequest request = new PassIssueRequest("김하윤", PhoneNumber.of("01012345678"), LocalDate.of(2026, 10, 15),
				Money.of(150000), PaymentMethod.CARD, true, "2026-10-v1");

		assertThat(request.toString()).doesNotContain("김하윤").doesNotContain("12345678").doesNotContain("5678")
			.contains("2026-10-v1");
	}

}
