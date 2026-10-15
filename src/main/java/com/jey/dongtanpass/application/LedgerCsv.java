package com.jey.dongtanpass.application;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 발급 장부를 CSV로 쓴다. 연계 학원이 표 계산 프로그램으로 연다.
 *
 * <p>UTF-8에 BOM을 붙인다(없으면 표 계산 프로그램이 한글을 깨뜨린다). 모든 칸을 따옴표로 감싸고 줄은 CRLF로 끝낸다.
 * 학생 이름은 연계 학원이 입력한 값이라, 수식으로 읽힐 첫 글자 앞에는 {@code '}를 붙인다(CSV 인젝션).
 */
final class LedgerCsv {

	private static final List<String> HEADER = List.of("패스 번호", "학생 이름", "발급일", "만료일", "상태", "결제일", "결제 금액",
			"결제 수단");

	private static final byte[] BOM = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

	// 표 계산 프로그램이 수식으로 읽는 첫 글자.
	private static final String FORMULA_START = "=+-@\t\r";

	private static final String LINE_END = "\r\n";

	private LedgerCsv() {
	}

	static byte[] write(List<LedgerRow> rows) {
		StringBuilder text = new StringBuilder(line(HEADER.stream()));
		for (LedgerRow row : rows) {
			text.append(line(Stream.of(row.passNo(), row.studentName(), row.issuedOn().toString(),
					row.expiresOn().toString(), row.state().label(), row.paidOn().toString(),
					row.paidAmount().amount().toPlainString(), row.paymentMethod().label())));
		}
		ByteArrayOutputStream file = new ByteArrayOutputStream();
		file.writeBytes(BOM);
		file.writeBytes(text.toString().getBytes(StandardCharsets.UTF_8));
		return file.toByteArray();
	}

	/** 한 칸. 따옴표로 감싸고 안의 따옴표는 둘로 쓴다. 수식으로 읽힐 첫 글자 앞에는 {@code '}를 붙인다. */
	static String cell(String value) {
		String text = value == null ? "" : value;
		if (!text.isEmpty() && FORMULA_START.indexOf(text.charAt(0)) >= 0) {
			text = "'" + text;
		}
		return "\"" + text.replace("\"", "\"\"") + "\"";
	}

	private static String line(Stream<String> cells) {
		return cells.map(LedgerCsv::cell).collect(Collectors.joining(",")) + LINE_END;
	}

}
