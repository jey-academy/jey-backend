package com.jey.core.shared;

/**
 * 그 일을 한 주체. 로그인한 계정이거나, 로그인 없이 일어난 일(배치, 웹훅, 이벤트 리스너)이다.
 *
 * <p>계정 ID를 {@code Long}으로 넘기지 않고 이 타입으로 넘긴다. 지점 ID 같은 다른 숫자와 자리가 바뀌어도 컴파일되는 일을 막고,
 * "행위자 없음"을 {@code null}이 아니라 {@link #none()}으로 적게 한다.
 * 지금 요청의 행위자는 {@code core.auth.api.CurrentActor}에서 얻는다.
 */
public sealed interface Actor permits Actor.User, Actor.None {

	static Actor user(long accountId) {
		return new User(accountId);
	}

	/** 로그인 없이 일어난 일. */
	static Actor none() {
		return None.INSTANCE;
	}

	/** 저장할 때 쓰는 값. 계정이 없으면 {@code null}. */
	Long idOrNull();

	/**
	 * @param accountId 계정 ID
	 */
	record User(long accountId) implements Actor {

		public User {
			if (accountId <= 0) {
				throw new IllegalArgumentException("계정 ID는 양수여야 한다: " + accountId);
			}
		}

		@Override
		public Long idOrNull() {
			return accountId;
		}

	}

	record None() implements Actor {

		private static final None INSTANCE = new None();

		@Override
		public Long idOrNull() {
			return null;
		}

	}

}
