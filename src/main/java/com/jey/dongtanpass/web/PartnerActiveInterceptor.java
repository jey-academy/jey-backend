package com.jey.dongtanpass.web;

import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.BusinessException;
import com.jey.dongtanpass.application.DongtanpassErrorCode;
import com.jey.dongtanpass.application.PartnerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

// 끈 연계 학원의 계정이 패스 모듈의 API를 쓰지 못하게 요청마다 막는다 (ADR-0034).
// 학원을 끄면 계정도 차단하지만, 차단은 학원을 끈 뒤에 따로 일어나 늦거나 실패할 수 있다. 막는 기준은 이 확인이다.
// 기초 모듈의 세션 확인(SessionAccountFilter)은 연계 학원을 모르므로 이것을 대신할 수 없다.
//
// 대상: 패스 모듈의 컨트롤러이고, 로그인한 사람이 연계 학원 계정인 요청. 직원과 관리자는 학원과 무관해 읽지 않는다.
// 역할 확인(AccessRuleInterceptor) 뒤에 돈다. 역할에 맞지 않는 요청은 학원을 읽기 전에 그쪽에서 끝난다.
// 예외는 컨트롤러 예외와 같은 경로(core.web)로 Problem Details가 된다.
class PartnerActiveInterceptor implements HandlerInterceptor {

	private static final String MODULE_PACKAGE = "com.jey.dongtanpass";

	private final PartnerService partnerService;

	PartnerActiveInterceptor(PartnerService partnerService) {
		this.partnerService = partnerService;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!(handler instanceof HandlerMethod handlerMethod) || !inModule(handlerMethod)) {
			return true;
		}
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)
				|| user.role() != UserRole.PARTNER) {
			return true;
		}
		if (!partnerService.isActive(user.partnerId())) {
			throw new BusinessException(DongtanpassErrorCode.PARTNER_INACTIVE);
		}
		return true;
	}

	private static boolean inModule(HandlerMethod handlerMethod) {
		String packageName = handlerMethod.getBeanType().getPackageName();
		return packageName.equals(MODULE_PACKAGE) || packageName.startsWith(MODULE_PACKAGE + ".");
	}

}
