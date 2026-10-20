package com.jey.billing.domain;

/**
 * 결제를 장부에 쓰지 못한 이유. {@link #GOOGLE_UNAVAILABLE}만 구글 쪽 장애이고(간격을 두고 다시 시도한 끝),
 * 나머지는 사람이 장부나 설정을 고쳐야 하는 이유라 자동으로 다시 하지 않는다(관리자가 고친 뒤 다시 반영).
 * 이름은 DB에 그대로 저장되고 관리자 화면이 안내 문구를 고르는 데 쓴다. 한 번 쓴 이름은 바꾸지 않는다.
 */
public enum LedgerSyncReason {

	/** 구글이 응답하지 않거나(시간 초과, 연결 끊김) 5xx·429로 답했고, 다시 시도한 횟수를 다 썼다. */
	GOOGLE_UNAVAILABLE,

	/** 대상 월의 탭({@code YYYY-MM})이 없다. */
	TAB_NOT_FOUND,

	/** 그 탭에 반 이름과 같은 반 블록이 없다. */
	BLOCK_NOT_FOUND,

	/** 반 블록에 빈 줄이 남아 있지 않다. */
	NO_EMPTY_ROW,

	/** 머리글 줄이나 앱이 쓰는 칸(대상 월 칸 포함)의 머리글이 없다. */
	HEADER_NOT_FOUND,

	/** 그 지점의 장부가 설정에 없거나, 설정한 스프레드시트가 없다. */
	NOT_CONFIGURED,

	/** 학생에게 연락처가 하나도 없다. */
	NO_CONTACT,

	/** 반 나눔 청구(줄이 둘 이상)라 사람이 적는다. */
	SPLIT_CLASS,

	/** 서비스 계정이 그 장부에 쓸 권한이 없다. */
	ACCESS_DENIED

}
