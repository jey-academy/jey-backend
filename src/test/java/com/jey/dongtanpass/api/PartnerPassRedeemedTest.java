package com.jey.dongtanpass.api;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;

import com.jey.core.shared.Actor;
import com.jey.core.shared.EventMetadata;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PartnerPassRedeemedTest {

	// 이벤트 이름은 감사 로그의 행위 이름이 되고, 미완료 발행 기록에도 적혀 있다. 바꾸지 않는다.
	@Test
	void 이벤트_이름과_대상은_정해져_있다() {
		PartnerPassRedeemed event = new PartnerPassRedeemed(EventMetadata.create(PartnerPassRedeemed.TYPE,
				PartnerPassRedeemed.VERSION, 1L, Actor.none(),
				Clock.fixed(Instant.parse("2026-10-20T01:00:00Z"), ZoneId.of("Asia/Seoul"))), 7L, 3L, 42L);

		assertThat(PartnerPassRedeemed.TYPE).isEqualTo("dongtanpass.partner-pass-redeemed");
		assertThat(event.subject()).contains(PassRefs.pass(7));
	}

	// 이름·번호·금액을 싣지 않는다. ID만 싣는다(금액은 사용 기록에 있다).
	@Test
	void 이벤트의_칸은_ID뿐이다() {
		assertThat(Arrays.stream(PartnerPassRedeemed.class.getRecordComponents()).map(c -> c.getName()))
			.containsExactly("metadata", "passId", "partnerId", "studentId");
	}

}
