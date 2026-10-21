package com.jey.billing.domain.ledger;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 시험용 장부 값. 구글 시트 값 API가 돌려주는 모양({@code List<List<String>>}, 줄 끝의 빈 칸은 빠진다)으로 만든다.
 */
final class LedgerSamples {

	private LedgerSamples() {
	}

	/** 가린 동탄관 장부 샘플(10월 탭). 전화번호는 모두 가짜 번호로 바꿔 두었다. */
	static List<List<String>> dongtanOctober() {
		try (InputStream in = LedgerSamples.class.getResourceAsStream("/ledger/dongtan-sample.csv")) {
			return trimTrailingBlanks(parseCsv(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** 따옴표 안의 쉼표·줄바꿈을 지원하는 작은 CSV 읽기(시험용). */
	static List<List<String>> parseCsv(String text) {
		List<List<String>> rows = new ArrayList<>();
		List<String> row = new ArrayList<>();
		StringBuilder cell = new StringBuilder();
		boolean quoted = false;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (quoted) {
				if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
					cell.append('"');
					i++;
				} else if (c == '"') {
					quoted = false;
				} else {
					cell.append(c);
				}
			} else if (c == '"') {
				quoted = true;
			} else if (c == ',') {
				row.add(cell.toString());
				cell.setLength(0);
			} else if (c == '\n') {
				row.add(cell.toString());
				cell.setLength(0);
				rows.add(row);
				row = new ArrayList<>();
			} else if (c != '\r') {
				cell.append(c);
			}
		}
		if (!cell.isEmpty() || !row.isEmpty()) {
			row.add(cell.toString());
			rows.add(row);
		}
		return rows;
	}

	/** 구글 API처럼 줄 끝의 빈 칸을 뺀다. */
	static List<List<String>> trimTrailingBlanks(List<List<String>> rows) {
		List<List<String>> result = new ArrayList<>();
		for (List<String> row : rows) {
			int end = row.size();
			while (end > 0 && row.get(end - 1).isEmpty()) {
				end--;
			}
			result.add(new ArrayList<>(row.subList(0, end)));
		}
		return result;
	}

	/** 줄을 복사해 칸 하나를 바꾼다(0부터 센 행·열). */
	static List<List<String>> with(List<List<String>> rows, int rowIndex, int columnIndex, String value) {
		List<List<String>> copy = new ArrayList<>();
		for (List<String> row : rows) {
			copy.add(new ArrayList<>(row));
		}
		List<String> target = copy.get(rowIndex);
		while (target.size() <= columnIndex) {
			target.add("");
		}
		target.set(columnIndex, value);
		return copy;
	}

}
