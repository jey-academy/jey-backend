package com.jey.core.sheets.api;

import java.util.List;
import java.util.Map;

/**
 * 구글 스프레드시트의 한 탭을 읽고 쓴다(ADR-0047). 무엇을 어디에 쓸지(장부의 모양)는 쓰는 모듈이 정한다.
 * 구글 장애는 {@link SheetsUnavailable}(간격을 두고 다시 시도할 일), 그 밖의 거절은 {@link SheetsRejected}(사람이 고칠 일)다.
 * 트랜잭션 안에서 부르지 않는다(HTTP를 기다리는 동안 DB 연결을 쥐지 않게).
 */
public interface Spreadsheets {

	/** 서비스 계정 키가 있는지. 없으면 구글에 요청할 수 없다(부르면 예외). */
	boolean enabled();

	/** 탭의 값(A1부터, 보이는 글자 그대로). 줄 끝의 빈 칸과 끝의 빈 줄은 빠져 있다. */
	List<List<String>> read(String spreadsheetId, String tab);

	/**
	 * 칸마다 따로 쓴다(줄 전체를 쓰지 않는다 — 쓰지 않는 칸은 건드리지 않는다). 입력한 그대로 쓴다(시트가 날짜·수식으로 바꾸지 않는다).
	 *
	 * @param cells A1 표기의 칸({@code D10}) → 값(글자 또는 숫자)
	 */
	void write(String spreadsheetId, String tab, Map<String, Object> cells);

	/**
	 * 탭의 표 끝에 한 줄을 붙인다(A열부터, 입력한 그대로). 구글이 줄을 끼워 넣으므로 동시에 붙여도 서로 덮지 않는다.
	 * 결과를 모르는 실패(시간 초과, 연결 끊김, 5xx)라면 붙었을 수 있다 — 같은 줄을 두 번 붙이지 않으려면 부르는 쪽이 먼저 읽어 본다.
	 *
	 * @param row 칸의 값(글자 또는 숫자). 비울 칸은 빈 글자
	 * @return 붙인 줄의 번호(1부터)
	 */
	int append(String spreadsheetId, String tab, List<Object> row);

}
