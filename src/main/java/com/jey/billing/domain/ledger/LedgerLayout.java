package com.jey.billing.domain.ledger;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.jey.billing.domain.LedgerSyncReason;

/**
 * 장부 탭 한 달치의 모양: 머리글 줄에서 찾은 칸 위치와 반 블록.
 *
 * <ul>
 * <li>머리글 줄은 위에서 몇 줄 안에 "성명" 칸이 있는 첫 줄이다. 칸은 머리글 이름으로 찾는다(공백·줄바꿈 무시).
 * <li>반 블록은 맨 앞 칸(A열)에 값이 있는 줄에서 시작해 다음 A열 값 전까지다. 블록 이름은 그 칸의 첫 줄을 다듬은 것
 * ({@code "EJ5\nBH/SH"} → {@code EJ5})이고 앱의 반 이름과 같다.
 * <li>빈 줄은 앱이 쓰는 칸이 모두 빈 줄이다(No만 적힌 줄). 사람이 무엇이든 적은 줄은 쓰지 않는다.
 * </ul>
 *
 * <p>값은 구글 시트 값 API의 모양 그대로 받는다(A1부터, 줄 끝의 빈 칸과 끝의 빈 줄은 빠져 있다).
 * 받은 값 밖의 줄은 쓸 자리로 보지 않는다 — 블록 아래에 미리 깔아 둔 줄(No)만 쓴다.
 */
public final class LedgerLayout {

	private static final int HEADER_SEARCH_ROWS = 10;

	private static final int BLOCK_COLUMN = 0;

	private static final Pattern BLANKS = Pattern.compile("[\\s\\u00A0]+");

	private final List<List<String>> values;

	private final int headerIndex;

	private final Map<LedgerColumn, Integer> columns;

	private LedgerLayout(List<List<String>> values, int headerIndex, Map<LedgerColumn, Integer> columns) {
		this.values = values;
		this.headerIndex = headerIndex;
		this.columns = columns;
	}

	/**
	 * @param values 탭의 값(A1부터)
	 * @param month 대상 월. 그 달의 납부일 칸({@code 11월})을 찾는다
	 * @throws LedgerLayoutException 머리글 줄이나 앱이 쓰는 칸의 머리글이 없을 때({@link LedgerSyncReason#HEADER_NOT_FOUND})
	 */
	public static LedgerLayout parse(List<List<String>> values, YearMonth month) {
		List<List<String>> copy = new ArrayList<>();
		for (List<String> row : values) {
			copy.add(row == null ? List.of() : List.copyOf(row.stream().map(c -> c == null ? "" : c).toList()));
		}
		int headerIndex = headerIndex(copy);
		List<String> header = copy.get(headerIndex);
		Map<LedgerColumn, Integer> columns = new EnumMap<>(LedgerColumn.class);
		for (LedgerColumn column : LedgerColumn.values()) {
			String name = column == LedgerColumn.PAID_DATE ? month.getMonthValue() + "월" : column.header();
			int index = indexOf(header, name);
			if (index < 0) {
				throw new LedgerLayoutException(LedgerSyncReason.HEADER_NOT_FOUND, "머리글이 없다: " + name);
			}
			columns.put(column, index);
		}
		return new LedgerLayout(copy, headerIndex, columns);
	}

	private static int headerIndex(List<List<String>> values) {
		String name = normalize(LedgerColumn.NAME.header());
		for (int i = 0; i < Math.min(HEADER_SEARCH_ROWS, values.size()); i++) {
			if (values.get(i).stream().anyMatch(cell -> normalize(cell).equals(name))) {
				return i;
			}
		}
		throw new LedgerLayoutException(LedgerSyncReason.HEADER_NOT_FOUND, "머리글 줄이 없다");
	}

	private static int indexOf(List<String> header, String name) {
		String wanted = normalize(name);
		for (int i = 0; i < header.size(); i++) {
			if (normalize(header.get(i)).equals(wanted)) {
				return i;
			}
		}
		return -1;
	}

	private static String normalize(String text) {
		return BLANKS.matcher(text).replaceAll("");
	}

	/**
	 * 이 결제를 쓸 줄을 찾는다. 탭 어디에든 이 결제의 표시가 있으면 그 줄, 아니면 반 블록의 첫 빈 줄.
	 *
	 * @param classCode 반 이름(앞뒤 공백은 다듬는다)
	 * @param marker 이 결제의 표시({@link LedgerFormats#marker})
	 */
	public LedgerTarget find(String classCode, String marker) {
		for (int i = headerIndex + 1; i < values.size(); i++) {
			if (LedgerFormats.isMarker(cell(i, columns.get(LedgerColumn.NOTE)).strip(), marker)) {
				return new LedgerTarget.AlreadyWritten(i + 1);
			}
		}
		String code = classCode.strip();
		int start = -1;
		for (int i = headerIndex + 1; i < values.size(); i++) {
			if (!cell(i, BLOCK_COLUMN).isBlank() && blockName(cell(i, BLOCK_COLUMN)).equals(code)) {
				start = i;
				break;
			}
		}
		if (start < 0) {
			return new LedgerTarget.Missing(LedgerSyncReason.BLOCK_NOT_FOUND);
		}
		for (int i = start; i < values.size(); i++) {
			if (i > start && !cell(i, BLOCK_COLUMN).isBlank()) {
				break;
			}
			if (isEmptyIndex(i)) {
				return new LedgerTarget.EmptyRow(i + 1);
			}
		}
		return new LedgerTarget.Missing(LedgerSyncReason.NO_EMPTY_ROW);
	}

	private static String blockName(String cell) {
		return cell.lines().findFirst().orElse("").strip();
	}

	/** 그 줄(시트의 행 번호)의 앱이 쓰는 칸이 모두 비었는지. 쓰기 직전에 다시 읽은 모양으로 확인한다. */
	public boolean isEmptyRow(int row) {
		return isEmptyIndex(row - 1);
	}

	private boolean isEmptyIndex(int index) {
		return columns.values().stream().allMatch(column -> cell(index, column).isBlank());
	}

	/** 그 줄의 비고 칸(A1 표기). */
	public String noteCell(int row) {
		return LedgerFormats.column(columns.get(LedgerColumn.NOTE)) + row;
	}

	/** 그 줄의 비고 칸의 값(없으면 빈 문자열). */
	public String noteAt(int row) {
		return cell(row - 1, columns.get(LedgerColumn.NOTE));
	}

	/** 그 줄에 쓸 칸(A1 표기 → 값), 열 순서대로. 값이 없는 칸은 담지 않는다. */
	public Map<String, Object> cells(int row, LedgerRow ledgerRow) {
		Map<Integer, Object> byColumn = new TreeMap<>();
		ledgerRow.values().forEach((column, value) -> byColumn.put(columns.get(column), value));
		Map<String, Object> cells = new LinkedHashMap<>();
		byColumn.forEach((column, value) -> cells.put(LedgerFormats.column(column) + row, value));
		return cells;
	}

	private String cell(int index, int column) {
		if (index < 0 || index >= values.size()) {
			return "";
		}
		List<String> row = values.get(index);
		return column < row.size() ? row.get(column) : "";
	}

}
