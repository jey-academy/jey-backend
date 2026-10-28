/**
 * 제이G패스 모듈. 연계 학원과 그 계정, 패스 발급, 확인, 사용 처리.
 * 화면에 보이는 상품 이름은 "제이G패스"이고, 코드 이름(패키지, 테이블, API 경로, 오류 코드, 이벤트 이름)은 예전 이름
 * {@code dongtanpass}를 그대로 쓴다(ADR-0049).
 * JEY 지점의 사용 처리는 금액과 청구를 다루지 않는다. 수납 모듈과 잇는 방법은 그 모듈을 만들 때 정한다(ADR-0033).
 * 연계 학원의 사용 처리는 받을 금액을 계산해 남기지만 결제는 다루지 않는다(ADR-0041).
 * 다른 모듈은 {@code api} 패키지만 사용할 수 있다.
 */
@org.springframework.modulith.ApplicationModule(
		displayName = "Dongtanpass",
		allowedDependencies = { "core::shared", "core::auth", "core::audit", "core::member", "core::campus",
				"core::sheets", "billing::api" })
package com.jey.dongtanpass;
