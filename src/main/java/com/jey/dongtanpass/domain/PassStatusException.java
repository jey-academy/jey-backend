package com.jey.dongtanpass.domain;

/**
 * 지금 상태에서는 할 수 없는 일을 하려 했다. 부르는 쪽이 상태에 맞는 오류 코드로 바꾼다.
 * 패스만 던진다(생성자는 이 패키지 안). 발급 상태는 무엇이든 할 수 있으므로 그 상태로는 만들 수 없다.
 */
public class PassStatusException extends RuntimeException {

	private final PassStatus status;

	PassStatusException(PassStatus status) {
		super("패스가 " + status + " 상태라 할 수 없다");
		if (status == null || status == PassStatus.ISSUED) {
			throw new IllegalArgumentException("발급 상태가 아닌 상태로만 만든다: " + status);
		}
		this.status = status;
	}

	public PassStatus status() {
		return status;
	}

}
