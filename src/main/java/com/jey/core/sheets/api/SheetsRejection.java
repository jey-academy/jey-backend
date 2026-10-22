package com.jey.core.sheets.api;

/** 구글이 거절한 이유. 쓰는 모듈이 자기 미반영 이유로 바꾼다. */
public enum SheetsRejection {

	/** 그 이름의 탭이 없다(400 "Unable to parse range"). */
	TAB_NOT_FOUND,

	/** 서비스 계정이 그 파일에 권한이 없다(또는 키가 더는 유효하지 않다). 401·403. */
	ACCESS_DENIED,

	/** 그 스프레드시트가 없다. 404. */
	SPREADSHEET_NOT_FOUND,

	/** 위의 어느 것도 아닌 4xx. 요청이 틀렸을 수 있어 경고 로그를 남긴다. */
	REJECTED

}
