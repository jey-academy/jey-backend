package com.jey.core.notification.api;

/**
 * 알림을 받는 사람. 전화번호나 이름이 아니라 ID로 가리킨다. 어디로 보낼지(번호, 채널)는 실제로 보내는 쪽이 조회한다.
 */
public sealed interface Recipient permits Recipient.Guardian, Recipient.Account, Recipient.Admins {

	static Recipient guardian(long guardianId) {
		return new Guardian(guardianId);
	}

	static Recipient account(long accountId) {
		return new Account(accountId);
	}

	/** 관리자 전체. 운영자가 알아야 하는 일(납부 일지 반영 실패 등)에 쓴다. */
	static Recipient admins() {
		return Admins.INSTANCE;
	}

	/**
	 * 학부모 한 명.
	 *
	 * @param guardianId 학부모 ID
	 */
	record Guardian(long guardianId) implements Recipient {

		public Guardian {
			requirePositive(guardianId, "학부모");
		}

		@Override
		public String toString() {
			return "guardian:" + guardianId;
		}

	}

	/**
	 * 로그인 계정 하나(직원, 관리자, 연계 학원).
	 *
	 * @param accountId 계정 ID
	 */
	record Account(long accountId) implements Recipient {

		public Account {
			requirePositive(accountId, "계정");
		}

		@Override
		public String toString() {
			return "account:" + accountId;
		}

	}

	record Admins() implements Recipient {

		private static final Admins INSTANCE = new Admins();

		@Override
		public String toString() {
			return "admins";
		}

	}

	private static void requirePositive(long id, String kind) {
		if (id <= 0) {
			throw new IllegalArgumentException(kind + " ID는 양수여야 한다: " + id);
		}
	}

}
