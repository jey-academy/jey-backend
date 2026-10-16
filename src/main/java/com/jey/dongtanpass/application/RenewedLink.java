package com.jey.dongtanpass.application;

/**
 * 새로 만든 학부모 링크. 토큰 원문은 여기에만 있다(DB에 저장하지 않는다. 멱등 응답 보관에는 24시간 남는다 — ADR-0035).
 *
 * @param linkToken 새 링크의 토큰 원문
 */
public record RenewedLink(long id, String linkToken) {

	// 링크 토큰 원문이 로그에 찍히지 않게 한다.
	@Override
	public String toString() {
		return "RenewedLink[id=" + id + "]";
	}

}
