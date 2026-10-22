package com.jey.dongtanpass.web;

/** 연계 학원 장부의 값 설명(명세에 싣는다). 관리자 화면이 이유마다 할 일을 안내한다. */
final class PartnerLedgerSyncDocs {

	static final String KIND = "기록의 종류. PARTNER_DISCOUNT(그 학원이 JEY 패스로 할인해 주었다 — 금액이 있는 줄), "
			+ "USED_AT_JEY(그 학원이 발급한 패스가 JEY 지점에서 쓰였다)";

	static final String REASON = "미반영 이유. NOT_CONFIGURED(그 학원의 장부 연결이 없거나 스프레드시트를 찾지 못했다 — 서버 설정을 확인한다), "
			+ "TAB_NOT_FOUND(장부 파일에 'JEY 패스' 탭이 없다 — 그 이름의 탭을 만든다), "
			+ "HEADER_MISMATCH(탭의 1행 머리글이 정한 것과 다르다 — 1행을 '구분, 사용일, 학생, 패스 번호, 원래 수강료, 할인율, 할인액, 받을 금액, JEY 지점, 앱 표시'로 맞춘다), "
			+ "ACCESS_DENIED(서비스 계정에 장부 편집 권한이 없거나 키가 더는 유효하지 않다), "
			+ "GOOGLE_UNAVAILABLE(구글 장애가 오래 이어졌다), GOOGLE_REJECTED(구글이 요청을 거절했다 — 개발자 확인), "
			+ "APP_ERROR(앱 오류 — 개발자 확인)";

	private PartnerLedgerSyncDocs() {
	}

}
