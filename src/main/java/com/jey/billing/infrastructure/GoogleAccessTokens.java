package com.jey.billing.infrastructure;

/** 구글 요청의 인증 머리글({@code Authorization}) 값. 시험에서는 고정 값으로 바꾼다. */
@FunctionalInterface
interface GoogleAccessTokens {

	String authorization();

	/** 키가 없을 때. 장부 연결이 꺼져 있으면 부를 일이 없다(스프레드시트를 정했으면 키가 있어야 서버가 뜬다). */
	static GoogleAccessTokens disabled() {
		return () -> {
			throw new IllegalStateException("장부 서비스 계정 키가 없다");
		};
	}

}
