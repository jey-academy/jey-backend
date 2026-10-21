package com.jey.billing.domain;

/** 지금 상태의 장부 반영으로는 할 수 없는 일이다(미반영이 아닌 줄을 다시 반영). 서비스가 409로 바꾼다. */
public class LedgerSyncStateException extends RuntimeException {

	private final LedgerSyncStatus current;

	LedgerSyncStateException(LedgerSyncStatus current) {
		super("지금 상태(" + current + ")의 장부 반영으로는 할 수 없다");
		this.current = current;
	}

	public LedgerSyncStatus current() {
		return current;
	}

}
