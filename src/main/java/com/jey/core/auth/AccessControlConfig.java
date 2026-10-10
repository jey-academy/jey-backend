package com.jey.core.auth;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@Configuration(proxyBeanMethods = false)
class AccessControlConfig implements WebMvcConfigurer {

	private static final Logger log = LoggerFactory.getLogger(AccessControlConfig.class);

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new AccessRuleInterceptor());
	}

	// 모든 빈이 만들어진 뒤에 한 번, 등록된 컨트롤러 메서드 전부의 접근 규칙 표시를 확인한다.
	// 하나라도 빠졌으면 예외로 서버 시작을 멈춘다. 요청이 올 때까지 기다리면 그 API를 처음 부른 사람이 발견하게 된다.
	// 매핑 빈이 만들어지려면 이 설정(인터셉터)이 먼저 필요하므로, 바로 주입받지 않고 필요할 때 꺼낸다.
	@Bean
	static SmartInitializingSingleton accessRuleStartupCheck(ObjectProvider<RequestMappingHandlerMapping> mappings) {
		return () -> {
			List<RequestMappingHandlerMapping> found = mappings.stream().toList();
			// 웹 요청을 받지 않는 컨텍스트다. 확인할 것이 없다.
			if (found.isEmpty()) {
				return;
			}
			int checked = AccessRule.requireAllMarked(found.stream()
					.flatMap(mapping -> mapping.getHandlerMethods().values().stream())
					.toList());
			// 로그인 API가 항상 있으므로 0이 나올 수 없다. 0이면 검사가 매핑을 보지 못하고 있는 것이고,
			// 그대로 통과시키면 검사가 없는 것과 같다.
			if (checked == 0) {
				throw new IllegalStateException("접근 규칙을 확인할 컨트롤러 메서드를 하나도 찾지 못했다. 서버를 시작하지 않는다.");
			}
			log.info("접근 규칙 확인: 컨트롤러 메서드 {}개", checked);
		};
	}

}
