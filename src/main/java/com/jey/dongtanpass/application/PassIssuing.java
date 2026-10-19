package com.jey.dongtanpass.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.function.Supplier;

import com.jey.core.shared.BusinessException;
import com.jey.core.shared.Money;
import com.jey.core.shared.UniqueKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 두 방향의 발급(연계 학원, JEY 지점)이 같이 쓰는 규칙: 근거 결제의 검증과 패스 번호가 겹칠 때의 재시도.
 */
final class PassIssuing {

	/** 패스 번호가 겹칠 때 발급을 몇 번까지 해 보는지. 32글자 여섯 자리라 실제로는 거의 겹치지 않는다. */
	static final int MAX_PASS_NO_ATTEMPTS = 3;

	// 마이그레이션에 적은 제약 이름. 저장이 어느 제약에 걸렸는지 이것으로 가린다.
	private static final String PASS_NO_UNIQUE_KEY = "uk_dongtanpass_pass_no";

	private static final Logger log = LoggerFactory.getLogger(PassIssuing.class);

	private PassIssuing() {
	}

	/**
	 * 근거 결제를 본다. 반 수강료와 같은 금액 규칙이다.
	 *
	 * @throws BusinessException 결제일이 오늘보다 뒤일 때, 결제 금액이 0원이거나 1원 단위가 아닐 때
	 */
	static void requirePayment(LocalDate paidOn, Money paidAmount, Clock clock) {
		if (paidOn.isAfter(LocalDate.now(clock))) {
			throw new BusinessException(DongtanpassErrorCode.PAID_ON_IN_FUTURE);
		}
		if (paidAmount.isZero() || !paidAmount.isWholeWon()) {
			throw new BusinessException(DongtanpassErrorCode.INVALID_PAID_AMOUNT);
		}
	}

	/**
	 * 발급 한 번(트랜잭션 하나)을 돌린다. 패스 번호가 이미 있는 번호와 겹치면 통째로 다시 한다
	 * ({@link #MAX_PASS_NO_ATTEMPTS}번까지. 그 뒤에는 저장 예외를 그대로 던진다). 다른 제약에 걸린 것은 그대로 던진다.
	 *
	 * @param issuer 로그에 남길 발급처(예: {@code partnerId=3}). 개인정보를 넣지 않는다
	 */
	static <T> T withPassNoRetry(Supplier<T> attempt, String issuer) {
		for (int tries = 1;; tries++) {
			try {
				return attempt.get();
			}
			catch (DataIntegrityViolationException ex) {
				// 다른 제약에 걸린 것은 그대로 던진다(서버 오류로 드러나야 한다).
				if (!UniqueKeys.isViolationOf(ex, PASS_NO_UNIQUE_KEY)) {
					throw ex;
				}
				if (tries == MAX_PASS_NO_ATTEMPTS) {
					// 일반 저장 오류와 가려 볼 수 있게 남긴다. 계속 겹치면 번호 공간이나 생성기를 봐야 한다.
					log.error("패스 번호가 {}번 연속 겹쳐 발급을 포기한다: {}", tries, issuer);
					throw ex;
				}
				log.warn("패스 번호가 이미 있는 번호와 겹쳐 다시 발급한다: attempt={}, {}", tries, issuer);
			}
		}
	}

}
