package com.jey.billing.application;

/** 장부 잠금 저장소(Redis)에 닿지 못했다. 장애로 보고 간격을 두고 다시 시도한다(장부를 고칠 일이 아니다). */
public class LedgerLockUnavailable extends RuntimeException {

	public LedgerLockUnavailable(String message, Throwable cause) {
		super(message, cause);
	}

}
