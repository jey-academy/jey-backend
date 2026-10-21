package com.jey.billing.application;

import java.time.Duration;
import java.util.Optional;

/**
 * 같은 장부(스프레드시트)에는 한 번에 한 결제만 쓴다. 두 결제가 같은 빈 줄을 집지 않게 한다(서버가 둘이어도).
 * 잠금은 정해진 시간이 지나면 저절로 풀린다(쥔 서버가 내려가도 남지 않게). 한 번의 시도(읽기와 쓰기)보다 길다.
 */
public interface LedgerLock {

	/**
	 * @param wait 다른 쪽이 쥐고 있으면 이만큼 기다린다
	 * @return 잡았으면 풀 때 닫을 손잡이, 기다려도 못 잡았으면 비어 있다
	 * @throws LedgerLockUnavailable 잠금 저장소에 닿지 못했을 때
	 */
	Optional<Held> tryAcquire(String spreadsheetId, Duration wait);

	interface Held extends AutoCloseable {

		/**
		 * 닫아도 풀지 않고 정해진 시간이 다할 때 풀리게 둔다. 쓰기의 결과를 모를 때(시간 초과 등) 부른다 — 구글이 늦게 반영할 수 있어서,
		 * 바로 풀면 다음 결제가 같은 빈 줄을 집고 늦게 들어온 쓰기에 덮인다.
		 */
		void keepUntilExpiry();

		/** 내가 쥔 잠금일 때만 푼다(시간이 지나 남이 잡은 잠금은 건드리지 않는다). 풀지 못해도 예외를 던지지 않는다(시간이 지나면 풀린다). */
		@Override
		void close();

	}

}
