package com.jey.billing.application;

import com.jey.billing.domain.LedgerSyncReason;

/** 구글이 장애가 아닌 이유로 거절했다(탭 없음, 권한 없음, 파일 없음 등). 다시 해도 낫지 않으므로 미반영으로 둔다. */
public class LedgerSheetsRejected extends RuntimeException {

	private final LedgerSyncReason reason;

	public LedgerSheetsRejected(LedgerSyncReason reason, String message) {
		super(message);
		this.reason = reason;
	}

	public LedgerSyncReason reason() {
		return reason;
	}

}
