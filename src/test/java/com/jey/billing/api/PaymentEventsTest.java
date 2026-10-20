package com.jey.billing.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.jey.core.shared.Money;

class PaymentEventsTest {

	// 이벤트 이름은 감사 로그의 행위 이름이 되고, 미완료 발행 기록에도 적혀 있다. 바꾸지 않는다.
	@Test
	void 이벤트_이름과_대상은_정해져_있다() {
		assertThat(PaymentCompleted.TYPE).isEqualTo("billing.payment-completed");
		assertThat(PaymentCompleted.VERSION).isEqualTo(1);
		assertThat(PaymentCanceled.TYPE).isEqualTo("billing.payment-canceled");
		assertThat(PaymentCanceled.VERSION).isEqualTo(1);
		assertThat(new PaymentCompleted(null, 1, 2, 3, "2026-11", PaymentMethod.CASH, Money.ZERO, Money.ZERO, null)
			.subject()).hasValueSatisfying(ref -> {
				assertThat(ref.type()).isEqualTo("student");
				assertThat(ref.id()).isEqualTo(3);
			});
		assertThat(new PaymentCanceled(null, 1, 2, 3).subject())
			.hasValueSatisfying(ref -> assertThat(ref.id()).isEqualTo(3));
	}

}
