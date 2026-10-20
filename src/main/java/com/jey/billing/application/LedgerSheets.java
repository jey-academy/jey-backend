package com.jey.billing.application;

import java.util.List;
import java.util.Map;

/**
 * 장부 스프레드시트의 한 탭을 읽고 쓴다. 구현은 구글 시트 API를 부른다(infrastructure).
 * 구글 장애는 {@link LedgerSheetsUnavailable}(간격을 두고 다시 시도), 그 밖의 거절은 {@link LedgerSheetsRejected}(사람이 고칠 이유)다.
 * 트랜잭션 안에서 부르지 않는다(HTTP를 기다리는 동안 DB 연결을 쥐지 않게).
 */
public interface LedgerSheets {

	/** 탭의 값(A1부터, 보이는 글자 그대로). 줄 끝의 빈 칸과 끝의 빈 줄은 빠져 있다. */
	List<List<String>> read(String spreadsheetId, String tab);

	/**
	 * 칸마다 따로 쓴다(줄 전체를 쓰지 않는다 — 앱이 쓰지 않는 칸은 건드리지 않는다). 입력한 그대로 쓴다(시트가 날짜·수식으로 바꾸지 않는다).
	 *
	 * @param cells A1 표기의 칸({@code D10}) → 값(글자 또는 숫자)
	 */
	void write(String spreadsheetId, String tab, Map<String, Object> cells);

}
