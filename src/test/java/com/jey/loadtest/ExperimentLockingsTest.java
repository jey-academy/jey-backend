package com.jey.loadtest;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import com.jey.core.shared.Money;
import com.jey.dongtanpass.domain.LinkToken;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassNumber;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.PaymentMethod;
import com.jey.dongtanpass.domain.Redemption;
import com.jey.dongtanpass.domain.RedemptionRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 비교 실험의 pessimistic·unique-first 방식이 정한 대로 읽고 저장하는지 본다. 실제 동작(잠금·데드락·응답 시간)은 부하 시험(k6)이 잰다. Docker가 필요 없다.
class ExperimentLockingsTest {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final PassRepository passes = mock(PassRepository.class);

	private final RedemptionRepository redemptions = mock(RedemptionRepository.class);

	@Test
	void 비관적_락은_패스를_행_잠금으로_읽고_패스를_먼저_저장한다() {
		Pass pass = pass();
		when(passes.findByIdForUpdate(11L)).thenReturn(Optional.of(pass));
		PessimisticRedeemLocking locking = new PessimisticRedeemLocking(passes, redemptions);

		assertThat(locking.load(11L)).containsSame(pass);
		Redemption redemption = pass.redeem(5L, 1L, Instant.parse("2026-10-18T01:00:00Z"));
		locking.store(pass, redemption);

		verify(passes, never()).findById(any());
		InOrder order = inOrder(passes, redemptions);
		order.verify(passes).saveAndFlush(pass);
		order.verify(redemptions).saveAndFlush(redemption);
	}

	// 운영 방식이 패스를 먼저 저장하도록 바꾸기 전의 순서다. 같은 패스를 동시에 쓰면 유니크 키가 먼저 막지만, 외래 키의 공유 잠금 때문에 데드락이 날 수 있다.
	@Test
	void 기록_먼저는_잠그지_않고_읽고_기록을_먼저_저장한다() {
		Pass pass = pass();
		when(passes.findById(11L)).thenReturn(Optional.of(pass));
		UniqueFirstRedeemLocking locking = new UniqueFirstRedeemLocking(passes, redemptions);

		assertThat(locking.load(11L)).containsSame(pass);
		Redemption redemption = pass.redeem(5L, 1L, Instant.parse("2026-10-18T01:00:00Z"));
		locking.store(pass, redemption);

		verify(passes, never()).findByIdForUpdate(any());
		InOrder order = inOrder(passes, redemptions);
		order.verify(redemptions).saveAndFlush(redemption);
		order.verify(passes).saveAndFlush(pass);
	}

	private static Pass pass() {
		Pass pass = Pass.issue(7L, PassNumber.generate(RANDOM), LinkToken.generate(RANDOM).hash(), "김하윤", "idx",
				Instant.parse("2026-10-18T01:00:00Z"), ZoneId.of("Asia/Seoul"), LocalDate.of(2026, 10, 18),
				Money.of(150000), PaymentMethod.CARD);
		ReflectionTestUtils.setField(pass, "id", 11L);
		return pass;
	}

}
