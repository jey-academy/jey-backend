package com.jey.dongtanpass.domain;

/** 지금 상태에서는 할 수 없는 일을 하려 했다. 부르는 쪽이 상태에 맞는 오류 코드로 바꾼다. */
public class PassStatusException extends RuntimeException {

	private final PassStatus status;

	public PassStatusException(PassStatus status) {
		super("패스가 " + status + " 상태라 할 수 없다");
		this.status = status;
	}

	public PassStatus status() {
		return status;
	}

}
