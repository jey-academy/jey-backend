package com.jey.billing.domain.ledger;

import java.text.DecimalFormat;
import java.time.LocalDate;

import com.jey.core.shared.Money;
import com.jey.core.shared.PhoneNumber;

/** 장부에 쓰는 글자의 모양. 기존 장부에 사람이 적어 온 모양을 따른다. */
public final class LedgerFormats {

	private static final String MARKER_PREFIX = "앱:결제-";

	private static final int ALPHABET = 26;

	private LedgerFormats() {
	}

	/** {@code (010) 1234-5678}, 10자리는 {@code (011) 123-4567}. 원문 번호이므로 장부의 부모 전화 칸에만 쓴다. */
	public static String phone(PhoneNumber phone) {
		String digits = phone.value();
		int middleEnd = digits.length() - 4;
		return "(" + digits.substring(0, 3) + ") " + digits.substring(3, middleEnd) + "-" + digits.substring(middleEnd);
	}

	/** {@code 29,000원}. */
	public static String won(Money money) {
		return new DecimalFormat("#,##0").format(money.amount()) + "원";
	}

	/** {@code 10/31}(앞의 0 없이). 납부일 칸과 취소 표시에 쓴다. */
	public static String date(LocalDate date) {
		return date.getMonthValue() + "/" + date.getDayOfMonth();
	}

	/** 앱이 쓴 줄의 표시({@code 앱:결제-123}). 비고 칸에 쓰고, 다시 쓰기 전에 이것으로 찾는다. */
	public static String marker(long paymentId) {
		return MARKER_PREFIX + paymentId;
	}

	/** 결제를 취소했을 때 비고 칸의 값({@code 앱:결제-123 취소됨(10/31)}). */
	public static String canceledNote(long paymentId, LocalDate canceledOn) {
		return marker(paymentId) + " 취소됨(" + date(canceledOn) + ")";
	}

	/** 비고 칸의 값이 그 결제의 표시인지. 뒤에 취소 표시가 붙어 있어도 같은 결제다. {@code 앱:결제-1234}는 123이 아니다. */
	public static boolean isMarkerOf(String note, long paymentId) {
		return note != null && isMarker(note.strip(), marker(paymentId));
	}

	static boolean isMarker(String strippedNote, String marker) {
		return strippedNote.equals(marker) || strippedNote.startsWith(marker + " ");
	}

	/** 0부터 센 열 번호를 A1 표기의 글자로({@code 0 → A}, {@code 26 → AA}). */
	public static String column(int index) {
		StringBuilder letters = new StringBuilder();
		for (int n = index + 1; n > 0; n = (n - 1) / ALPHABET) {
			letters.insert(0, (char) ('A' + (n - 1) % ALPHABET));
		}
		return letters.toString();
	}

}
