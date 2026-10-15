package com.jey.dongtanpass.web;

import com.jey.dongtanpass.application.PartnerService;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class DongtanpassWebConfig implements WebMvcConfigurer {

	private final PartnerService partnerService;

	DongtanpassWebConfig(PartnerService partnerService) {
		this.partnerService = partnerService;
	}

	// 가장 뒤에 둔다. 역할 확인(순서를 정하지 않은 AccessRuleInterceptor)이 먼저 돌아야, 역할에 맞지 않는 요청이 학원을 읽지 않는다.
	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new PartnerActiveInterceptor(partnerService)).order(Ordered.LOWEST_PRECEDENCE);
	}

}
