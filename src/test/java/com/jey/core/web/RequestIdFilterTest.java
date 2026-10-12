package com.jey.core.web;

import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class RequestIdFilterTest {

	private final RequestIdFilter filter = new RequestIdFilter();

	private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/something");

	private final MockHttpServletResponse response = new MockHttpServletResponse();

	@AfterEach
	void clearLogContext() {
		MDC.clear();
	}

	@Test
	void ID가_없으면_새로_만들어_응답_헤더와_로그_문맥에_넣는다() throws Exception {
		AtomicReference<String> duringChain = new AtomicReference<>();

		filter.doFilter(request, response, (req, res) -> duringChain.set(MDC.get("requestId")));

		String id = response.getHeader("X-Request-Id");
		assertThat(id).matches("[0-9a-f]{32}");
		assertThat(duringChain).hasValue(id);
		assertThat(RequestIdFilter.of(request)).isEqualTo(id);
	}

	@Test
	void 요청마다_다른_ID를_만든다() throws Exception {
		MockHttpServletResponse another = new MockHttpServletResponse();

		filter.doFilter(request, response, (req, res) -> {
		});
		filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/something"), another, (req, res) -> {
		});

		assertThat(another.getHeader("X-Request-Id")).isNotEqualTo(response.getHeader("X-Request-Id"));
	}

	// 앞단(Nginx)이 정해 준 ID를 그대로 써야 앞단의 로그와 이어 볼 수 있다.
	@ParameterizedTest
	@ValueSource(strings = { "abcd1234", "0123456789abcdef0123456789abcdef", "Abc_-123",
			"a123456789b123456789c123456789d123456789e123456789f123456789abcd" })
	void 형식에_맞는_ID는_그대로_쓴다(String incoming) throws Exception {
		request.addHeader("X-Request-Id", incoming);
		AtomicReference<String> duringChain = new AtomicReference<>();

		filter.doFilter(request, response, (req, res) -> duringChain.set(MDC.get("requestId")));

		assertThat(response.getHeader("X-Request-Id")).isEqualTo(incoming);
		assertThat(duringChain).hasValue(incoming);
	}

	// 요청을 구분하려는 값 때문에 요청이 실패하면 안 된다. 버리고 새로 만든다.
	// 줄바꿈이나 긴 값을 그대로 쓰면 로그에 가짜 줄을 끼워 넣을 수 있다.
	@ParameterizedTest
	@ValueSource(strings = { "", " ", "abc1234", "a123456789b123456789c123456789d123456789e123456789f123456789abcde",
			"abcd1234\nERROR 가짜 줄", "abcd 1234", "요청아이디12345678", "abcd1234;drop", "../../etc/passwd" })
	void 형식에_맞지_않는_ID는_버리고_새로_만든다(String incoming) throws Exception {
		request.addHeader("X-Request-Id", incoming);
		AtomicReference<Integer> status = new AtomicReference<>();

		filter.doFilter(request, response, (req, res) -> status.set(response.getStatus()));

		assertThat(response.getHeader("X-Request-Id")).matches("[0-9a-f]{32}").isNotEqualTo(incoming);
		assertThat(status).hasValue(200);
	}

	// 앞단이 형식 밖의 값을 보내면 모든 요청이 새 ID를 받는다. 그것을 알아챌 흔적을 남기되 값은 찍지 않는다.
	// 로그인 없이도 보낼 수 있는 값이라 평소에는 찍지 않는 수준(DEBUG)이다.
	@Test
	void 버린_ID는_길이만_DEBUG로_남긴다(CapturedOutput output) throws Exception {
		var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RequestIdFilter.class);
		var before = logger.getLevel();
		logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
		try {
			request.addHeader("X-Request-Id", "discarded value!");

			filter.doFilter(request, response, (req, res) -> {
			});
		}
		finally {
			logger.setLevel(before);
		}

		assertThat(output).contains("length=16").doesNotContain("discarded value!");
	}

	@Test
	void 버린_ID는_평소에는_로그에_남지_않는다(CapturedOutput output) throws Exception {
		request.addHeader("X-Request-Id", "discarded value!");

		filter.doFilter(request, response, (req, res) -> {
		});

		assertThat(output).doesNotContain("length=16").doesNotContain("discarded value!");
	}

	// ID 없이 온 요청은 버린 것이 아니다.
	@Test
	void ID_없이_온_요청은_DEBUG로도_남기지_않는다(CapturedOutput output) throws Exception {
		var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(RequestIdFilter.class);
		var before = logger.getLevel();
		logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
		try {
			filter.doFilter(request, response, (req, res) -> {
			});
		}
		finally {
			logger.setLevel(before);
		}

		assertThat(output).doesNotContain("버리고 새로 만든다");
	}

	// 스레드는 다음 요청이 다시 쓴다. 남아 있으면 다음 요청의 로그에 앞 요청의 ID와 계정이 찍힌다.
	@Test
	void 요청이_끝나면_로그_문맥을_비운다() throws Exception {
		filter.doFilter(request, response, (req, res) -> MDC.put("accountId", "7"));

		assertThat(MDC.get("requestId")).isNull();
		assertThat(MDC.get("accountId")).isNull();
	}

	@Test
	void 요청이_예외로_끝나도_로그_문맥을_비운다() {
		FilterChain failing = (req, res) -> {
			MDC.put("accountId", "7");
			throw new ServletException("boom");
		};

		assertThatThrownBy(() -> filter.doFilter(request, response, failing)).isInstanceOf(ServletException.class);

		assertThat(MDC.get("requestId")).isNull();
		assertThat(MDC.get("accountId")).isNull();
	}

	// 필터가 sendError로 끝낸 요청은 컨테이너가 오류 경로로 다시 보낸다. 같은 요청이므로 ID도 같아야 한다.
	@Test
	void 오류_경로로_다시_들어온_요청은_처음의_ID를_다시_쓴다() throws Exception {
		filter.doFilter(request, response, (req, res) -> {
		});
		String first = response.getHeader("X-Request-Id");
		// 컨테이너가 오류 경로로 다시 보낼 때 붙이는 속성이다. 필터가 오류 경로에서 돌지 말지는 이 속성으로 가린다.
		request.setDispatcherType(DispatcherType.ERROR);
		request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/something");
		MockHttpServletResponse errorResponse = new MockHttpServletResponse();
		AtomicReference<String> duringChain = new AtomicReference<>();

		filter.doFilter(request, errorResponse, (req, res) -> duringChain.set(MDC.get("requestId")));

		assertThat(duringChain).hasValue(first);
		assertThat(errorResponse.getHeader("X-Request-Id")).isEqualTo(first);
		assertThat(MDC.get("requestId")).isNull();
	}

	// 앞단의 설정 실수로 헤더가 둘 실려 올 수 있다. 첫 값만 본다.
	@Test
	void 헤더가_여러_개_오면_첫_값을_쓴다() throws Exception {
		request.addHeader("X-Request-Id", "first-id-0123456789");
		request.addHeader("X-Request-Id", "second-id-0123456789");

		filter.doFilter(request, response, (req, res) -> {
		});

		assertThat(response.getHeaders("X-Request-Id")).containsExactly("first-id-0123456789");
	}

	@Test
	void 헤더_이름의_대소문자는_가리지_않는다() throws Exception {
		request.addHeader("x-request-id", "lowercase-name-0123456789");

		filter.doFilter(request, response, (req, res) -> {
		});

		assertThat(response.getHeader("X-Request-Id")).isEqualTo("lowercase-name-0123456789");
	}

	// 오류를 잡는 필터가 남기는 로그에도 ID가 찍히려면 그 필터보다 바깥이어야 한다.
	@Test
	void 모든_필터보다_앞에서_돈다() {
		assertThat(filter.getOrder()).isEqualTo(Integer.MIN_VALUE);
	}

}
