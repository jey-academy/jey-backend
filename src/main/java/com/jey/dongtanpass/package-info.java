/**
 * 동탄고 패스 모듈. 연계 학원과 그 계정, 패스 발급, 확인, 사용 처리.
 * 사용 처리는 금액과 청구를 다루지 않는다. 수납 모듈과 잇는 방법은 그 모듈을 만들 때 정한다(ADR-0033).
 * 다른 모듈은 {@code api} 패키지만 사용할 수 있다.
 */
@org.springframework.modulith.ApplicationModule(
		displayName = "Dongtanpass",
		allowedDependencies = { "core::shared", "core::auth", "core::audit", "core::member", "core::campus",
				"billing::api" })
package com.jey.dongtanpass;
