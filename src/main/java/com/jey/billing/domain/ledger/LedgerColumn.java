package com.jey.billing.domain.ledger;

/**
 * 앱이 쓰는 장부 칸. 칸은 위치가 아니라 머리글 이름으로 찾는다(공백·줄바꿈은 무시하고 비교).
 * 대상 월의 납부일 칸({@link #PAID_DATE})만 이름이 달마다 다르다({@code 11월}).
 * 성명~매월 납입금액 다섯 칸은 결제 대행사 파일을 만들 때 그대로 복사하므로 장부에서 순서를 바꾸지 않는다(앱은 위치를 정하지 않는다).
 */
public enum LedgerColumn {

	SCHOOL("학교"),
	NAME("성명"),
	PHONE("부모 전화"),
	CHARGE("납입금액"),
	CONTENT("결제 내용"),
	MONTHLY_BASE("매월 납입금액(8회기준)"),
	PAID_DATE(null),
	TUITION("수강료"),
	BOOK_FEE("교재비"),
	THIS_MONTH("이번달 납부 내용"),
	NOTE("비고");

	private final String header;

	LedgerColumn(String header) {
		this.header = header;
	}

	/** 머리글 이름. 납부일 칸은 대상 월에 따라 다르므로 여기서 정하지 않는다({@code null}). */
	String header() {
		return header;
	}

}
