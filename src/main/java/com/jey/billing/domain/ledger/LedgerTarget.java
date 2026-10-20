package com.jey.billing.domain.ledger;

import com.jey.billing.domain.LedgerSyncReason;

/** 결제를 장부의 어느 줄에 쓸지 찾은 결과. 행 번호는 시트의 행 번호(1부터)다. */
public sealed interface LedgerTarget {

	/** 그 탭에 이 결제의 표시가 이미 있다 — 쓰지 않는다(앞선 시도가 쓰고 응답을 잃은 경우). */
	record AlreadyWritten(int row) implements LedgerTarget {
	}

	/** 반 블록에서 찾은 빈 줄. */
	record EmptyRow(int row) implements LedgerTarget {
	}

	/** 쓸 자리가 없다({@link LedgerSyncReason#BLOCK_NOT_FOUND}, {@link LedgerSyncReason#NO_EMPTY_ROW}). */
	record Missing(LedgerSyncReason reason) implements LedgerTarget {
	}

}
