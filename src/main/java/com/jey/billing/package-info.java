/**
 * 수납 모듈. 수강료 계산, 청구, 결제, 납부 일지 연동.
 * 할인이 어느 모듈에서 왔는지 모른다. gopass 등 할인 모듈에 의존하지 않는다.
 * 다른 모듈은 {@code api} 패키지만 사용할 수 있다.
 */
@org.springframework.modulith.ApplicationModule(
		displayName = "Billing",
		allowedDependencies = { "core::shared", "core::member", "core::campus" })
package com.jey.billing;
