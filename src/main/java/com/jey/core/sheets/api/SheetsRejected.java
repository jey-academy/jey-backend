package com.jey.core.sheets.api;

/** 구글이 장애가 아닌 이유로 거절했다(탭 없음, 권한 없음, 파일 없음 등). 다시 해도 낫지 않는다. */
public class SheetsRejected extends RuntimeException {

	private final SheetsRejection rejection;

	public SheetsRejected(SheetsRejection rejection, String message) {
		super(message);
		this.rejection = rejection;
	}

	public SheetsRejection rejection() {
		return rejection;
	}

}
