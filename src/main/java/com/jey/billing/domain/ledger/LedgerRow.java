package com.jey.billing.domain.ledger;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import com.jey.core.shared.Money;

/**
 * 장부의 한 줄에 쓸 값(칸 → 값). 금액은 숫자({@code Long})로, 날짜·전화·표시·설명은 글자로 둔다
 * (시트에는 입력한 그대로 쓰므로 {@code 10/31}이 날짜로 바뀌지 않는다. 쉼표 서식은 시트에 맡긴다).
 * 값이 없는 칸(학교 표시 없음, 교재비 0원)은 담지 않는다 — 쓰지 않고 비워 둔다.
 */
public record LedgerRow(Map<LedgerColumn, Object> values) {

	public LedgerRow {
		values = Collections.unmodifiableMap(new EnumMap<>(values));
	}

	public static LedgerRow of(LedgerEntry entry) {
		Map<LedgerColumn, Object> values = new EnumMap<>(LedgerColumn.class);
		if (entry.schoolLabel() != null && !entry.schoolLabel().isBlank()) {
			values.put(LedgerColumn.SCHOOL, entry.schoolLabel());
		}
		values.put(LedgerColumn.NAME, entry.studentName() + "(" + entry.className() + ")");
		values.put(LedgerColumn.PHONE, LedgerFormats.phone(entry.phone()));
		values.put(LedgerColumn.CHARGE, number(entry.chargeAmount()));
		String content = content(entry);
		values.put(LedgerColumn.CONTENT, content);
		values.put(LedgerColumn.MONTHLY_BASE, number(entry.monthlyBase()));
		values.put(LedgerColumn.PAID_DATE, LedgerFormats.date(entry.paidOn()));
		values.put(LedgerColumn.TUITION, number(entry.tuition()));
		if (entry.bookFee().isZero()) {
			values.put(LedgerColumn.THIS_MONTH, content);
		} else {
			values.put(LedgerColumn.BOOK_FEE, number(entry.bookFee()));
			values.put(LedgerColumn.THIS_MONTH, content + " + 교재비 (" + LedgerFormats.won(entry.bookFee()) + ")");
		}
		values.put(LedgerColumn.NOTE, LedgerFormats.marker(entry.paymentId()));
		return new LedgerRow(values);
	}

	// 11월 9회분 수강료 (동탄고 패스 + 형제 할인 5%)
	private static String content(LedgerEntry entry) {
		String base = entry.targetMonth().getMonthValue() + "월 " + entry.sessions() + "회분 수강료";
		if (entry.discountNames().isEmpty()) {
			return base;
		}
		return base + " (" + String.join(" + ", entry.discountNames()) + ")";
	}

	private static Long number(Money money) {
		return money.amount().longValueExact();
	}

}
