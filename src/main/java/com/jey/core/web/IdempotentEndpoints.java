package com.jey.core.web;

import com.jey.core.shared.Idempotent;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.UnsatisfiedServletRequestParameterException;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;

/**
 * 요청이 가리키는 컨트롤러 메서드에 {@link Idempotent}가 붙어 있는지 본다.
 * 필터는 컨트롤러를 고르기 전에 돌기 때문에, 컨트롤러를 고르는 쪽에 직접 물어본다.
 */
@Component
class IdempotentEndpoints {

	private final ObjectProvider<RequestMappingHandlerMapping> handlerMapping;

	// 필터는 일찍 만들어진다. MVC 구성이 끝난 뒤에 꺼내 쓰도록 미룬다. 같은 타입의 빈이 여럿이라 이름으로 고른다.
	// @Lazy로 미루면 안 된다. 대리 객체를 거치면 final인 getHandler가 빈 객체에서 실행된다.
	IdempotentEndpoints(
			@Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> handlerMapping) {
		this.handlerMapping = handlerMapping;
	}

	boolean requiresKey(HttpServletRequest request) {
		try {
			// 컨트롤러를 고르려면 경로가 해석돼 있어야 한다. 평소에는 DispatcherServlet이 하는 일이다.
			if (!ServletRequestPathUtils.hasParsedRequestPath(request)) {
				ServletRequestPathUtils.parseAndCache(request);
			}
			HandlerExecutionChain chain = handlerMapping.getObject().getHandler(request);
			return chain != null && chain.getHandler() instanceof HandlerMethod method
					&& method.hasMethodAnnotation(Idempotent.class);
		}
		catch (HttpRequestMethodNotSupportedException | HttpMediaTypeNotSupportedException
				| HttpMediaTypeNotAcceptableException | UnsatisfiedServletRequestParameterException ex) {
			// 경로는 있지만 맞는 메서드가 없는 요청이다(지원하지 않는 메서드, 맞지 않는 본문 타입 등).
			// 어느 메서드도 실행되지 않고 뒤에서 같은 이유로 거절된다.
			return false;
		}
		catch (Exception ex) {
			// 컨트롤러를 고르지 못한 까닭을 모른다. "키가 필요 없다"로 답하면 다시 보낸 요청이 조용히 두 번 실행된다.
			// 위에 적은 것 말고는 여기로 온다. 넓게 잡아 넘기지 않는다.
			throw new IllegalStateException("멱등 키가 필요한 요청인지 확인하지 못했다", ex);
		}
	}

}
