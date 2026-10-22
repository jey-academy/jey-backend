package com.jey.core.sheets;

/** 구글 요청의 인증 머리글({@code Authorization}) 값. 시험에서는 고정 값으로 바꾼다. */
@FunctionalInterface
interface GoogleAccessTokens {

	String authorization();

	/** 키가 있는지. 없으면 시트 연결이 꺼진 것이다. */
	default boolean enabled() {
		return true;
	}

	/** 키가 없을 때. 연결이 꺼져 있으면 쓰는 쪽이 부르지 않는다(파일을 정했으면 키가 있어야 서버가 뜬다 — 쓰는 모듈이 본다). */
	static GoogleAccessTokens disabled() {
		return new GoogleAccessTokens() {

			@Override
			public String authorization() {
				throw new IllegalStateException("구글 시트 서비스 계정 키가 없다");
			}

			@Override
			public boolean enabled() {
				return false;
			}

		};
	}

}
