package com.jey.core.auth;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@Configuration(proxyBeanMethods = false)
class AccessControlConfig implements WebMvcConfigurer {

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new AccessRuleInterceptor());
	}

	// 모든 빈이 만들어진 뒤에 한 번, 등록된 컨트롤러 메서드 전부의 접근 규칙 표시를 확인한다.
	// 하나라도 빠졌으면 예외로 서버 시작을 멈춘다. 요청이 올 때까지 기다리면 그 API를 처음 부른 사람이 발견하게 된다.
	// 매핑 빈이 만들어지려면 이 설정(인터셉터)이 먼저 필요하므로, 바로 주입받지 않고 필요할 때 꺼낸다.
	@Bean
	static SmartInitializingSingleton accessRuleStartupCheck(ObjectProvider<RequestMappingHandlerMapping> mappings) {
		return () -> AccessRule.requireAllMarked(mappings.stream()
				.flatMap(mapping -> mapping.getHandlerMethods().values().stream())
				.toList());
	}

}
