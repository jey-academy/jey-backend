package com.jey.dongtanpass.domain;

/** 지금 상태의 장부 반영으로는 할 수 없는 일이다(미반영이 아닌 줄을 다시 반영). 서비스가 409로 바꾼다. */
public class PartnerLedgerSyncStateException extends RuntimeException {

	private final PartnerLedgerStatus current;

	PartnerLedgerSyncStateException(PartnerLedgerStatus current) {
		super("지금 상태(" + current + ")의 연계 학원 장부 반영으로는 할 수 없다");
		this.current = current;
	}

	public PartnerLedgerStatus current() {
		return current;
	}

}
