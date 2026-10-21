package com.jey.billing.web;

/** 장부 미반영 이유의 설명(명세에 싣는다). 관리자 화면이 이유마다 할 일을 안내한다. */
final class LedgerSyncDocs {

	static final String REASON = "미반영 이유. TAB_NOT_FOUND(대상 월 탭 YYYY-MM이 없다 — 탭을 만든다), "
			+ "BLOCK_NOT_FOUND(그 반 블록이 없다 — A열 칸의 첫 줄(줄바꿈 앞 글자)이 반 이름과 같은지 본다), "
			+ "NO_EMPTY_ROW(반 블록에 빈 줄이 없다 — No만 적힌 줄을 더한다), "
			+ "MARKER_NOT_FOUND(장부에 쓴 결제를 취소했는데 그 줄의 비고에서 앱:결제-N 표시를 찾지 못했다 — 직접 취소 표시를 하거나 표시를 되돌린다), "
			+ "HEADER_NOT_FOUND(머리글이 없다 — 위 10줄 안에 '성명'이 있는 머리글 줄과, 앱이 쓰는 칸 이름·대상 월 칸(예: 11월)이 있는지 본다), "
			+ "NOT_CONFIGURED(그 지점의 장부 연결이 없거나 스프레드시트를 찾지 못했다), "
			+ "ACCESS_DENIED(서비스 계정에 장부 편집 권한이 없거나 키가 더는 유효하지 않다), NO_CONTACT(학생에게 연락처가 없다), "
			+ "SPLIT_CLASS(반 나눔 청구라 사람이 적는다 — 다시 반영해도 같다), GOOGLE_UNAVAILABLE(구글이나 잠금 저장소의 장애가 오래 이어졌다), "
			+ "GOOGLE_REJECTED(구글이 요청을 거절했다 — 개발자 확인), APP_ERROR(앱 오류 — 개발자 확인)";

	private LedgerSyncDocs() {
	}

}
