package com.jey.dongtanpass.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

// 패스 사용 하나를 연계 학원 장부에 쓰는 일의 상태. Docker가 필요 없다.
class PartnerLedgerSyncTest {

	private static final Instant NOW = Instant.parse("2026-11-03T03:00:00Z");

	@Test
	void 처음에는_바로_시도할_수_있는_대기_상태다() {
		PartnerLedgerSync sync = newSync();

		assertThat(sync.getStatus()).isEqualTo(PartnerLedgerStatus.PENDING);
		assertThat(sync.getAttempts()).isZero();
		assertThat(sync.getNextAttemptAt()).isEqualTo(NOW);
		assertThat(sync.getPassId()).isEqualTo(7);
		assertThat(sync.getPartnerId()).isEqualTo(3);
		assertThat(sync.getKind()).isEqualTo(PartnerLedgerKind.PARTNER_DISCOUNT);
	}

	@Test
	void 맡으면_시도_중이_되고_시도_횟수와_시각을_남긴다() {
		PartnerLedgerSync sync = newSync();

		assertThat(sync.claim(NOW)).isTrue();

		assertThat(sync.getStatus()).isEqualTo(PartnerLedgerStatus.IN_PROGRESS);
		assertThat(sync.getAttempts()).isEqualTo(1);
		assertThat(sync.getLastAttemptAt()).isEqualTo(NOW);
		assertThat(sync.getNextAttemptAt()).isNull();
	}

	@Test
	void 다음_시도_시각_전에는_맡지_못한다() {
		PartnerLedgerSync sync = claimed();
		sync.retryLater(NOW, null);

		assertThat(sync.claim(NOW.plusSeconds(59))).isFalse();
		assertThat(sync.claim(NOW.plusSeconds(60))).isTrue();
	}

	@Test
	void 시도_중인_줄은_5분이_지나기_전에는_다시_맡지_못한다() {
		PartnerLedgerSync sync = claimed();

		assertThat(sync.claim(NOW.plus(PartnerLedgerSync.STALE_AFTER))).isFalse();
		assertThat(sync.claim(NOW.plus(PartnerLedgerSync.STALE_AFTER).plusMillis(1))).isTrue();
		assertThat(sync.getAttempts()).isEqualTo(2);
	}

	@Test
	void 오래_멈춘_시도가_한도까지_거듭되면_맡지_않고_앱_오류로_미반영한다() {
		PartnerLedgerSync sync = newSync();
		Instant at = NOW;
		for (int i = 0; i < PartnerLedgerSync.MAX_ATTEMPTS; i++) {
			assertThat(sync.claim(at)).isTrue();
			at = at.plus(PartnerLedgerSync.STALE_AFTER).plusSeconds(1);
		}

		assertThat(sync.claim(at)).isFalse();
		assertThat(sync.getStatus()).isEqualTo(PartnerLedgerStatus.UNSYNCED);
		assertThat(sync.getReason()).isEqualTo(PartnerLedgerReason.APP_ERROR);
	}

	@Test
	void 쓰면_반영됨이고_줄_번호와_시각을_남긴다() {
		PartnerLedgerSync sync = claimed();

		sync.written(12, NOW);

		assertThat(sync.getStatus()).isEqualTo(PartnerLedgerStatus.SYNCED);
		assertThat(sync.getSheetRow()).isEqualTo(12);
		assertThat(sync.getSyncedAt()).isEqualTo(NOW);
		assertThat(sync.claim(NOW.plusSeconds(3600))).isFalse();
	}

	@Test
	void 구글_장애는_1분_5분_15분_1시간_3시간_뒤에_다시_하고_그다음_실패는_미반영이다() {
		PartnerLedgerSync sync = newSync();
		Instant at = NOW;
		for (Duration delay : PartnerLedgerSync.RETRY_DELAYS) {
			assertThat(sync.claim(at)).isTrue();
			sync.retryLater(at, null);
			assertThat(sync.getStatus()).isEqualTo(PartnerLedgerStatus.PENDING);
			assertThat(sync.getNextAttemptAt()).isEqualTo(at.plus(delay));
			at = at.plus(delay);
		}
		assertThat(sync.claim(at)).isTrue();

		sync.retryLater(at, null);

		assertThat(sync.getStatus()).isEqualTo(PartnerLedgerStatus.UNSYNCED);
		assertThat(sync.getReason()).isEqualTo(PartnerLedgerReason.GOOGLE_UNAVAILABLE);
	}

	@Test
	void 구글이_알려_준_기다릴_시간이_더_길면_따르되_3시간까지다() {
		PartnerLedgerSync sync = claimed();
		sync.retryLater(NOW, Duration.ofMinutes(10));
		assertThat(sync.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));

		PartnerLedgerSync other = claimed();
		other.retryLater(NOW, Duration.ofDays(1));
		assertThat(other.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofHours(3)));
	}

	@Test
	void 사람이_고칠_이유면_미반영이고_자동으로_다시_하지_않는다() {
		PartnerLedgerSync sync = claimed();

		sync.unsynced(PartnerLedgerReason.HEADER_MISMATCH);

		assertThat(sync.getStatus()).isEqualTo(PartnerLedgerStatus.UNSYNCED);
		assertThat(sync.getReason()).isEqualTo(PartnerLedgerReason.HEADER_MISMATCH);
		assertThat(sync.getNextAttemptAt()).isNull();
		assertThat(sync.claim(NOW.plusSeconds(86_400))).isFalse();
	}

	@Test
	void 관리자가_다시_반영하면_이유를_지우고_시도_횟수를_처음부터_센다() {
		PartnerLedgerSync sync = claimed();
		sync.unsynced(PartnerLedgerReason.TAB_NOT_FOUND);

		sync.retryByAdmin(NOW.plusSeconds(60));

		assertThat(sync.getStatus()).isEqualTo(PartnerLedgerStatus.PENDING);
		assertThat(sync.getReason()).isNull();
		assertThat(sync.getAttempts()).isZero();
		assertThat(sync.claim(NOW.plusSeconds(60))).isTrue();
	}

	@Test
	void 미반영이_아니면_다시_반영할_수_없다() {
		PartnerLedgerSync pending = newSync();
		assertThatThrownBy(() -> pending.retryByAdmin(NOW)).isInstanceOf(PartnerLedgerSyncStateException.class);

		PartnerLedgerSync synced = claimed();
		synced.written(3, NOW);
		assertThatThrownBy(() -> synced.retryByAdmin(NOW)).isInstanceOf(PartnerLedgerSyncStateException.class);
	}

	@Test
	void 시도_중이_아니면_결과를_적을_수_없다() {
		PartnerLedgerSync sync = newSync();

		assertThatThrownBy(() -> sync.written(3, NOW)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> sync.retryLater(NOW, null)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> sync.unsynced(PartnerLedgerReason.APP_ERROR)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void 종류와_시각은_비울_수_없다() {
		assertThatThrownBy(() -> PartnerLedgerSync.pending(7, 3, null, NOW)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> PartnerLedgerSync.pending(7, 3, PartnerLedgerKind.USED_AT_JEY, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private static PartnerLedgerSync newSync() {
		return PartnerLedgerSync.pending(7, 3, PartnerLedgerKind.PARTNER_DISCOUNT, NOW);
	}

	private static PartnerLedgerSync claimed() {
		PartnerLedgerSync sync = newSync();
		sync.claim(NOW);
		return sync;
	}

}
