package com.jey.billing.domain.ledger;

import com.jey.billing.domain.LedgerSyncReason;

/** 탭의 모양을 읽지 못했다(머리글이 없다). 문구에 시트의 값을 싣지 않는다. */
public class LedgerLayoutException extends RuntimeException {

	private final LedgerSyncReason reason;

	public LedgerLayoutException(LedgerSyncReason reason, String message) {
		super(message);
		this.reason = reason;
	}

	public LedgerSyncReason reason() {
		return reason;
	}

}
