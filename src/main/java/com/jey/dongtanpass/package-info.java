/**
 * 동탄고 패스 모듈. 패스 발급, 확인, 사용 처리.
 * 사용 처리 때 billing에 청구 생성을 요청한다(dongtanpass → billing 한 방향).
 * 다른 모듈은 {@code api} 패키지만 사용할 수 있다.
 */
@org.springframework.modulith.ApplicationModule(
		displayName = "Dongtanpass",
		allowedDependencies = { "core::shared", "core::member", "core::campus", "billing::api" })
package com.jey.dongtanpass;
