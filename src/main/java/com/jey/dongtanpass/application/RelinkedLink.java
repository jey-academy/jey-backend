package com.jey.dongtanpass.application;

import java.util.Objects;

/**
 * 학부모가 다시 받은 링크.
 *
 * @param linkToken 새 링크의 토큰 원문. 이 응답에만 있다
 */
public record RelinkedLink(String linkToken) {

	public RelinkedLink {
		Objects.requireNonNull(linkToken, "linkToken");
	}

	// 링크 토큰 원문이 로그에 찍히지 않게 한다.
	@Override
	public String toString() {
		return "RelinkedLink[linkToken=***]";
	}

}
