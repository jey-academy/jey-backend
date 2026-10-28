package com.jey.dongtanpass.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.jey.core.shared.Money;
import com.jey.dongtanpass.domain.PartnerLedgerKind;

/**
 * 연계 학원 장부의 한 줄(A~J, ADR-0048). 값은 입력한 그대로 쓴다(금액은 숫자, 날짜·할인율은 글자). 전화번호는 싣지 않는다.
 * 학생 이름은 연계 학원 사용 내역 화면과 같은 범위다(발급 때의 이름, 고지·동의의 범위 — ADR-0035).
 * 마지막 칸의 앱 표시({@code 앱:패스-{패스 ID}})로 같은 기록을 두 번 쓰지 않는다.
 */
final class PartnerLedgerRow {

	/** 1행의 머리글. 관리자가 준비하고, 앱은 같은지만 본다(다르면 쓰지 않는다). */
	static final List<String> HEADER = List.of("구분", "사용일", "학생", "패스 번호", "원래 수강료", "할인율", "할인액", "받을 금액",
			"JEY 지점", "앱 표시");

	private static final int MARKER_COLUMN = HEADER.size() - 1;

	private final List<Object> cells;

	private PartnerLedgerRow(List<Object> cells) {
		this.cells = List.copyOf(cells);
	}

	/** 그 학원이 JEY 발급 패스로 할인해 주었다. {@code campusName}은 패스를 발급한 JEY 지점. */
	static PartnerLedgerRow partnerDiscount(long passId, LocalDate usedOn, String studentName, String passNo,
			Money originalFee, BigDecimal discountRate, Money discountAmount, Money payableAmount, String campusName) {
		return new PartnerLedgerRow(List.of(label(PartnerLedgerKind.PARTNER_DISCOUNT), usedOn.toString(), studentName,
				passNo, won(originalFee), percent(discountRate), won(discountAmount), won(payableAmount), campusName,
				marker(passId)));
	}

	/** 그 학원이 발급한 패스가 JEY 지점에서 쓰였다. {@code campusName}은 쓴 지점. */
	static PartnerLedgerRow usedAtJey(long passId, LocalDate usedOn, String studentName, String passNo, String campusName) {
		return new PartnerLedgerRow(List.of(label(PartnerLedgerKind.USED_AT_JEY), usedOn.toString(), studentName, passNo,
				"", "", "", "", campusName, marker(passId)));
	}

	List<Object> cells() {
		return cells;
	}

	/** 탭의 1행이 머리글과 같은지(칸마다 앞뒤 공백은 무시). 빈 탭이면 다르다. */
	static boolean headerMatches(List<List<String>> values) {
		if (values.isEmpty()) {
			return false;
		}
		return values.getFirst().stream().map(String::strip).toList().equals(HEADER);
	}

	/** 그 패스의 앱 표시가 있는 줄의 번호(1부터). 없으면 비어 있다. */
	static Optional<Integer> rowOf(List<List<String>> values, long passId) {
		String marker = marker(passId);
		for (int i = 0; i < values.size(); i++) {
			List<String> row = values.get(i);
			if (row.size() > MARKER_COLUMN && row.get(MARKER_COLUMN).strip().equals(marker)) {
				return Optional.of(i + 1);
			}
		}
		return Optional.empty();
	}

	static String marker(long passId) {
		return "앱:패스-" + passId;
	}

	private static String label(PartnerLedgerKind kind) {
		return switch (kind) {
			case PARTNER_DISCOUNT -> "제이G패스 할인";
			case USED_AT_JEY -> "JEY에서 사용";
		};
	}

	private static Long won(Money money) {
		return money.amount().longValueExact();
	}

	// 0.05 → "5%", 0.075 → "7.5%"
	private static String percent(BigDecimal rate) {
		return rate.movePointRight(2).stripTrailingZeros().toPlainString() + "%";
	}

}
