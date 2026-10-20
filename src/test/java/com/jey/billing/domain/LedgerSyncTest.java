package com.jey.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.Test;

class LedgerSyncTest {

	private static final Instant NOW = Instant.parse("2026-10-31T03:00:00Z");

	private static final LocalDate CANCELED_ON = LocalDate.of(2026, 11, 1);

	@Test
	void 처음에는_바로_시도할_수_있는_대기_상태다() {
		LedgerSync sync = newSync();

		assertThat(sync.getStatus()).isEqualTo(LedgerSyncStatus.PENDING);
		assertThat(sync.getAttempts()).isZero();
		assertThat(sync.getNextAttemptAt()).isEqualTo(NOW);
		assertThat(sync.getTargetMonth()).isEqualTo(YearMonth.of(2026, 11));
	}

	@Test
	void 맡으면_시도_중이_되고_시도_횟수와_시각을_남긴다() {
		LedgerSync sync = newSync();

		assertThat(sync.claim(NOW)).isTrue();

		assertThat(sync.getStatus()).isEqualTo(LedgerSyncStatus.IN_PROGRESS);
		assertThat(sync.getAttempts()).isEqualTo(1);
		assertThat(sync.getLastAttemptAt()).isEqualTo(NOW);
		assertThat(sync.getNextAttemptAt()).isNull();
	}

	@Test
	void 다음_시도_시각_전에는_맡지_못한다() {
		LedgerSync sync = claimed();
		sync.retryLater(NOW, null);

		assertThat(sync.claim(NOW.plusSeconds(59))).isFalse();
		assertThat(sync.claim(NOW.plusSeconds(60))).isTrue();
	}

	@Test
	void 시도_중인_줄은_5분이_지나기_전에는_다시_맡지_못한다() {
		LedgerSync sync = claimed();

		assertThat(sync.claim(NOW.plus(LedgerSync.STALE_AFTER))).isFalse();
		assertThat(sync.claim(NOW.plus(LedgerSync.STALE_AFTER).plusMillis(1))).isTrue();
		assertThat(sync.getAttempts()).isEqualTo(2);
	}

	@Test
	void 끝난_줄은_맡지_못한다() {
		LedgerSync sync = claimed();
		sync.written("2026-11", 10, NOW);

		assertThat(sync.claim(NOW.plusSeconds(3600))).isFalse();
	}

	@Test
	void 쓰면_반영됨이고_탭과_줄을_남긴다() {
		LedgerSync sync = claimed();

		sync.written("2026-11", 10, NOW);

		assertThat(sync.getStatus()).isEqualTo(LedgerSyncStatus.SYNCED);
		assertThat(sync.getSheetTab()).isEqualTo("2026-11");
		assertThat(sync.getSheetRow()).isEqualTo(10);
		assertThat(sync.getSyncedAt()).isEqualTo(NOW);
	}

	@Test
	void 구글_장애는_1분_5분_15분_1시간_3시간_뒤에_다시_하고_그다음_실패는_미반영이다() {
		LedgerSync sync = newSync();
		Instant at = NOW;
		for (Duration delay : new Duration[] { Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
				Duration.ofHours(1), Duration.ofHours(3) }) {
			assertThat(sync.claim(at)).isTrue();
			sync.retryLater(at, null);
			assertThat(sync.getStatus()).isEqualTo(LedgerSyncStatus.PENDING);
			assertThat(sync.getNextAttemptAt()).isEqualTo(at.plus(delay));
			at = at.plus(delay);
		}

		assertThat(sync.claim(at)).isTrue();
		sync.retryLater(at, null);

		assertThat(sync.getStatus()).isEqualTo(LedgerSyncStatus.UNSYNCED);
		assertThat(sync.getReason()).isEqualTo(LedgerSyncReason.GOOGLE_UNAVAILABLE);
		assertThat(sync.getAttempts()).isEqualTo(6);
		assertThat(sync.getNextAttemptAt()).isNull();
	}

	@Test
	void 구글이_알려_준_기다릴_시간이_더_길면_그것을_따른다() {
		LedgerSync sync = claimed();

		sync.retryLater(NOW, Duration.ofMinutes(3));

		assertThat(sync.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(3)));
	}

	@Test
	void 구글이_알려_준_기다릴_시간이_더_짧으면_정한_간격을_따른다() {
		LedgerSync sync = claimed();

		sync.retryLater(NOW, Duration.ofSeconds(10));

		assertThat(sync.getNextAttemptAt()).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
	}

	@Test
	void 장애가_아닌_이유는_바로_미반영이다() {
		LedgerSync sync = claimed();

		sync.unsynced(LedgerSyncReason.NO_EMPTY_ROW);

		assertThat(sync.getStatus()).isEqualTo(LedgerSyncStatus.UNSYNCED);
		assertThat(sync.getReason()).isEqualTo(LedgerSyncReason.NO_EMPTY_ROW);
		assertThat(sync.getNextAttemptAt()).isNull();
	}

	@Test
	void 관리자가_다시_반영하면_처음처럼_대기한다() {
		LedgerSync sync = claimed();
		sync.unsynced(LedgerSyncReason.TAB_NOT_FOUND);

		sync.retryByAdmin(NOW.plusSeconds(600));

		assertThat(sync.getStatus()).isEqualTo(LedgerSyncStatus.PENDING);
		assertThat(sync.getReason()).isNull();
		assertThat(sync.getAttempts()).isZero();
		assertThat(sync.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(600));
	}

	@Test
	void 미반영이_아닌_줄은_관리자가_다시_반영할_수_없다() {
		LedgerSync sync = claimed();
		sync.written("2026-11", 10, NOW);

		assertThatThrownBy(() -> sync.retryByAdmin(NOW)).isInstanceOf(LedgerSyncStateException.class);
		assertThatThrownBy(() -> newSync().retryByAdmin(NOW)).isInstanceOf(LedgerSyncStateException.class);
	}

	@Test
	void 시도_중이_아닌데_결과를_적으면_코드의_잘못이다() {
		assertThatThrownBy(() -> newSync().written("2026-11", 10, NOW)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> newSync().unsynced(LedgerSyncReason.NO_EMPTY_ROW))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> newSync().retryLater(NOW, null)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void 쓴_뒤에_결제를_취소하면_취소_표시를_하러_다시_대기한다() {
		LedgerSync sync = claimed();
		sync.written("2026-11", 10, NOW);

		sync.requestCancel(CANCELED_ON, NOW.plusSeconds(60));

		assertThat(sync.getStatus()).isEqualTo(LedgerSyncStatus.PENDING);
		assertThat(sync.getCanceledOn()).isEqualTo(CANCELED_ON);
		assertThat(sync.getAttempts()).isZero();
		assertThat(sync.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(60));
	}

	@Test
	void 미반영인_결제를_취소해도_다시_대기한다_앞선_시도가_썼을_수_있다() {
		LedgerSync sync = claimed();
		sync.unsynced(LedgerSyncReason.NO_EMPTY_ROW);

		sync.requestCancel(CANCELED_ON, NOW);

		assertThat(sync.getStatus()).isEqualTo(LedgerSyncStatus.PENDING);
		assertThat(sync.getReason()).isNull();
		assertThat(sync.isCancelRequested()).isTrue();
	}

	@Test
	void 쓰는_중에_취소되면_쓴_뒤에_취소_표시를_하러_다시_대기한다() {
		LedgerSync sync = claimed();

		sync.requestCancel(CANCELED_ON, NOW);
		assertThat(sync.getStatus()).isEqualTo(LedgerSyncStatus.IN_PROGRESS);

		sync.written("2026-11", 10, NOW.plusSeconds(1));

		assertThat(sync.getStatus()).isEqualTo(LedgerSyncStatus.PENDING);
		assertThat(sync.getSheetRow()).isEqualTo(10);
		assertThat(sync.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(1));
	}

	@Test
	void 취소_처리를_마치면_취소됨이고_표시했는지를_남긴다() {
		LedgerSync sync = claimed();
		sync.written("2026-11", 10, NOW);
		sync.requestCancel(CANCELED_ON, NOW);
		sync.claim(NOW);

		sync.cancelDone(true, NOW);

		assertThat(sync.getStatus()).isEqualTo(LedgerSyncStatus.CANCELED);
		assertThat(sync.isCancelMarked()).isTrue();
		assertThat(sync.getNextAttemptAt()).isNull();
	}

	@Test
	void 취소_요청이_없으면_취소_처리를_마칠_수_없다() {
		assertThatThrownBy(() -> claimed().cancelDone(false, NOW)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void 같은_취소가_다시_와도_결과가_같다() {
		LedgerSync sync = claimed();
		sync.written("2026-11", 10, NOW);
		sync.requestCancel(CANCELED_ON, NOW);
		sync.claim(NOW);
		sync.cancelDone(false, NOW);

		sync.requestCancel(CANCELED_ON.plusDays(1), NOW.plusSeconds(60));

		assertThat(sync.getStatus()).isEqualTo(LedgerSyncStatus.CANCELED);
		assertThat(sync.getCanceledOn()).isEqualTo(CANCELED_ON);
	}

	private static LedgerSync newSync() {
		return LedgerSync.pending(7L, 1L, 2L, YearMonth.of(2026, 11), NOW);
	}

	private static LedgerSync claimed() {
		LedgerSync sync = newSync();
		sync.claim(NOW);
		return sync;
	}

}
