package com.jey.core.shared;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class EventMetadataTest {

	private static final Instant NOW = Instant.parse("2026-11-03T01:21:05.123Z");

	private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));

	private static final UUID ID = UUID.fromString("0192f8a4-0000-7000-8000-000000000001");

	@Test
	void 시계로_발생_시각과_ID를_채운다() {
		EventMetadata metadata = EventMetadata.create("billing.payment-completed", 1, 1L, CLOCK);

		assertThat(metadata.eventType()).isEqualTo("billing.payment-completed");
		assertThat(metadata.version()).isEqualTo(1);
		assertThat(metadata.campusId()).isEqualTo(1L);
		assertThat(metadata.occurredAt()).isEqualTo(NOW);
		assertThat(metadata.eventId().version()).isEqualTo(7);
		assertThat(metadata.eventId().getMostSignificantBits() >>> 16).isEqualTo(NOW.toEpochMilli());
	}

	@Test
	void 만들_때마다_ID가_다르다() {
		assertThat(EventMetadata.create("billing.payment-completed", 1, 1L, CLOCK).eventId())
				.isNotEqualTo(EventMetadata.create("billing.payment-completed", 1, 1L, CLOCK).eventId());
	}

	@Test
	void 지점과_무관한_이벤트는_지점_없이_만들_수_있다() {
		assertThat(EventMetadata.create("core.user-created", 1, null, CLOCK).campusId()).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = { "billing.payment-completed", "gopass.pass-redeemed", "core.user-created", "billing.paid2" })
	void 이벤트_이름은_모듈_점_사건_형식이다(String eventType) {
		assertThat(new EventMetadata(ID, eventType, 1, NOW, 1L).eventType()).isEqualTo(eventType);
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "payment-completed", "billing.", ".payment-completed", "Billing.PaymentCompleted",
			"billing.payment_completed", "billing.payment-", "billing.payment.completed", "billing payment" })
	void 형식이_틀린_이벤트_이름은_거부한다(String eventType) {
		assertThatIllegalArgumentException().isThrownBy(() -> new EventMetadata(ID, eventType, 1, NOW, 1L));
	}

	@Test
	void 버전은_1_이상이어야_한다() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new EventMetadata(ID, "billing.payment-completed", 0, NOW, 1L));
	}

	@Test
	void 지점_ID는_양수여야_한다() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new EventMetadata(ID, "billing.payment-completed", 1, NOW, 0L));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> new EventMetadata(ID, "billing.payment-completed", 1, NOW, -1L));
	}

	@Test
	void ID_이름_시각은_비울_수_없다() {
		assertThatNullPointerException()
				.isThrownBy(() -> new EventMetadata(null, "billing.payment-completed", 1, NOW, 1L))
				.withMessage("eventId");
		assertThatNullPointerException().isThrownBy(() -> new EventMetadata(ID, null, 1, NOW, 1L))
				.withMessage("eventType");
		assertThatNullPointerException()
				.isThrownBy(() -> new EventMetadata(ID, "billing.payment-completed", 1, null, 1L))
				.withMessage("occurredAt");
	}

}
