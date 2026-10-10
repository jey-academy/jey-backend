package com.jey.core.auth;

import com.jey.core.auth.api.AnyRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.assertj.core.api.Assertions.assertThat;

// 검사 함수(AccessRule#requireAllMarked)가 맞게 동작해도, 서버 시작에 연결돼 있지 않으면 표시를 빠뜨린 API가 그대로 배포된다.
// 실제로 컨텍스트를 띄워서 시작이 멈추는지 확인한다. DB와 Redis 없이 MVC만 띄운다.
@ExtendWith(OutputCaptureExtension.class)
class AccessControlConfigTest {

	private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
			.withUserConfiguration(MvcConfig.class, AccessControlConfig.class);

	@Test
	void 표시_없는_컨트롤러_메서드가_있으면_서버가_뜨지_않는다() {
		runner.withBean(MarkedController.class).withBean(ForgottenController.class).run(context -> {
			assertThat(context).hasFailed();
			assertThat(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("접근 규칙이 잘못된 API가 있어 서버를 시작하지 않는다")
					.hasMessageContaining("ForgottenController#forgotten");
		});
	}

	@Test
	void 표시가_다_있으면_뜨고_확인한_개수를_로그에_남긴다(CapturedOutput output) {
		runner.withBean(MarkedController.class).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(output.getOut()).contains("접근 규칙 확인: 컨트롤러 메서드 1개");
		});
	}

	// 컨트롤러가 있어야 하는 서버에서 하나도 못 찾았다면 검사가 매핑을 보지 못하고 있는 것이다. 통과시키면 검사가 없는 것과 같다.
	@Test
	void 확인할_컨트롤러_메서드를_하나도_찾지_못하면_서버가_뜨지_않는다() {
		runner.run(context -> {
			assertThat(context).hasFailed();
			assertThat(NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("하나도 찾지 못했다");
		});
	}

	@Configuration(proxyBeanMethods = false)
	@EnableWebMvc
	static class MvcConfig {

	}

	@RestController
	static class ForgottenController {

		@GetMapping("/api/v1/sample/forgotten")
		String forgotten() {
			return "ok";
		}

	}

	@AnyRole
	@RestController
	static class MarkedController {

		@GetMapping("/api/v1/sample/marked")
		String marked() {
			return "ok";
		}

	}

}
