package com.jey.core.auth;

import com.jey.core.auth.api.AccessErrorCode;
import com.jey.core.auth.api.AllowedRoles;
import com.jey.core.auth.api.AnyRole;
import com.jey.core.auth.api.PublicEndpoint;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 로그인 없는 요청은 보통 보안 필터가 먼저 막아서 인터셉터까지 오지 않는다.
// 보안 설정에서 열어 둔 경로 아래에 로그인이 필요한 API가 있을 때만 인터셉터가 마지막으로 막는다.
// 그 경우를 서버 없이 직접 확인한다.
@ExtendWith(OutputCaptureExtension.class)
class AccessRuleInterceptorTest {

	private final AccessRuleInterceptor interceptor = new AccessRuleInterceptor();

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void 열린_경로라도_로그인이_필요한_표시면_익명은_인증_예외다() {
		loggedInAs(anonymous());

		assertThatThrownBy(() -> preHandle("anyone")).isInstanceOf(InsufficientAuthenticationException.class);
		assertThatThrownBy(() -> preHandle("deskOnly")).isInstanceOf(InsufficientAuthenticationException.class);
	}

	@Test
	void 로그인_정보가_아예_없어도_인증_예외다() {
		assertThatThrownBy(() -> preHandle("anyone")).isInstanceOf(InsufficientAuthenticationException.class);
		assertThatThrownBy(() -> preHandle("deskOnly")).isInstanceOf(InsufficientAuthenticationException.class);
	}

	@Test
	void 인증이_끝나지_않은_로그인_정보도_인증_예외다() {
		loggedInAs(UsernamePasswordAuthenticationToken.unauthenticated("desk", "password"));

		assertThatThrownBy(() -> preHandle("anyone")).isInstanceOf(InsufficientAuthenticationException.class);
	}

	// 보안 설정과 표시가 어긋난 것이다. 401만 나가면 원인을 찾을 단서가 없다.
	@Test
	void 열린_경로에서_로그인이_없어_막으면_로그에_남긴다(CapturedOutput output) {
		loggedInAs(anonymous());

		assertThatThrownBy(() -> preHandle("anyone")).isInstanceOf(InsufficientAuthenticationException.class);

		assertThat(output.getOut()).contains("접근 규칙은 로그인을 요구한다").contains("GET /api/v1/sample");
	}

	@Test
	void 로그인_없이_쓰는_표시는_익명도_통과한다() throws Exception {
		loggedInAs(anonymous());

		assertThat(preHandle("open")).isTrue();
	}

	@Test
	void 로그인한_누구나_쓰는_표시는_역할을_가리지_않는다() throws Exception {
		loggedInAs(userWith("ROLE_PARTNER"));

		assertThat(preHandle("anyone")).isTrue();
	}

	@Test
	void 적힌_역할이면_통과하고_아니면_거부한다() throws Exception {
		loggedInAs(userWith("ROLE_STAFF"));
		assertThat(preHandle("deskOnly")).isTrue();

		loggedInAs(userWith("ROLE_PARTNER"));
		assertThatThrownBy(() -> preHandle("deskOnly")).isInstanceOfSatisfying(BusinessException.class,
				ex -> assertThat(ex.getErrorCode()).isEqualTo(AccessErrorCode.ROLE_NOT_ALLOWED));
	}

	// 정적 자원 처리기나 라이브러리가 등록한 컨트롤러에는 표시를 붙일 수 없다. 로그인 여부는 보안 필터가 이미 확인했다.
	@Test
	void 컨트롤러_메서드가_아닌_처리기와_우리_패키지_밖의_컨트롤러는_통과한다() throws Exception {
		var request = new MockHttpServletRequest("GET", "/api/v1/sample");
		var response = new MockHttpServletResponse();

		assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
		assertThat(interceptor.preHandle(request, response,
				new HandlerMethod(new Object(), Object.class.getMethod("toString")))).isTrue();
	}

	private boolean preHandle(String methodName) throws Exception {
		var handler = new HandlerMethod(new SampleController(), SampleController.class.getDeclaredMethod(methodName));
		return interceptor.preHandle(new MockHttpServletRequest("GET", "/api/v1/sample"),
				new MockHttpServletResponse(), handler);
	}

	private static void loggedInAs(Authentication authentication) {
		SecurityContextHolder.getContext().setAuthentication(authentication);
	}

	private static Authentication anonymous() {
		return new AnonymousAuthenticationToken("key", "anonymousUser",
				AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));
	}

	private static Authentication userWith(String authority) {
		return UsernamePasswordAuthenticationToken.authenticated("someone", null,
				AuthorityUtils.createAuthorityList(authority));
	}

	static class SampleController {

		@PublicEndpoint
		void open() {
		}

		@AnyRole
		void anyone() {
		}

		@AllowedRoles({ UserRole.ADMIN, UserRole.STAFF })
		void deskOnly() {
		}

	}

}
