package com.jey.core.notification.api;

/**
 * 알림을 받는 사람. 전화번호나 이름이 아니라 ID로 가리킨다. 어디로 보낼지(번호, 채널)는 실제로 보내는 쪽이 조회한다.
 *
 * <p>글로 적은 모양({@code toString})은 사람이 읽는 로그용이다. 같은 알림을 가리는 키나 메시지의 값으로 쓰지 않는다.
 */
public sealed interface Recipient
		permits Recipient.PrimaryContact, Recipient.Guardian, Recipient.Account, Recipient.Admins {

	/**
	 * 학생의 대표 연락처. 학생에 대한 안내(패스 발급, 수납 완료)는 이것으로 보낸다.
	 *
	 * <p>맡기는 쪽은 학생만 알면 된다. 그 학생의 누구에게 가는지(어머니, 먼저 연결된 학부모, 학부모가 없으면 학생 본인)는
	 * 보내는 쪽이 보내는 시점에 고른다. 그래서 맡긴 뒤에 학부모가 바뀌어도 다시 맡긴 요청은 같은 알림이다.
	 *
	 * <p>연락처가 하나도 없는 학생이면 보낼 곳이 없다. 그때 보내는 쪽은 조용히 건너뛰지 않고
	 * 보내지 못했다는 사실(무슨 알림을, 어느 학생에게)을 기록으로 남긴다.
	 */
	static Recipient primaryContactOf(long studentId) {
		return new PrimaryContact(studentId);
	}

	/** 특정 학부모 한 명. 그 학부모를 가리켜야 할 때만 쓴다. 학생에 대한 안내는 {@link #primaryContactOf}로 보낸다. */
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
	 * 학생의 대표 연락처.
	 *
	 * @param studentId 학생 ID
	 */
	record PrimaryContact(long studentId) implements Recipient {

		public PrimaryContact {
			requirePositive(studentId, "학생");
		}

		@Override
		public String toString() {
			return "student-primary-contact:" + studentId;
		}

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
