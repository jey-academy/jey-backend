package com.jey.core.web;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.jey.TestcontainersConfiguration;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.filter.OncePerRequestFilter;

import static org.assertj.core.api.Assertions.assertThat;

// 필터에서 난 오류는 컨트롤러의 예외 처리기를 거치지 않고 서블릿 컨테이너로 올라간다.
// 컨테이너가 그것을 어떻게 응답하는지는 MockMvc로 볼 수 없어서 실제 서버를 띄워 확인한다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class FilterStageErrorTest {

	private static final String SECRET = "jdbc:mysql://secret-host:3306/jey";

	@Value("${local.server.port}")
	int port;

	// 서버가 응답하지 않을 때 테스트가 끝없이 기다리지 않게 한다.
	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	// 세션 저장소(Redis)가 응답하지 않을 때 세션 필터에서 이런 예외가 난다.
	// 401로 응답하면 프론트가 장애를 "로그인이 풀렸다"로 보고 로그인 화면으로 보낸다.
	@Test
	void 필터에서_난_예외는_500_Problem_Details로_응답한다() throws Exception {
		HttpResponse<String> response = get("/api/v1/test-filter/exception");

		assertInternalError(response);
	}

	// 오류가 보안 필터보다 앞에서 나도 CORS 헤더가 있어야 한다. 없으면 다른 출처의 프론트는 이 응답을 읽지 못하고
	// 네트워크 오류로 본다.
	@Test
	void 필터에서_난_오류의_응답도_다른_출처의_프론트가_읽을_수_있다() throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/test-filter/exception"))
				.timeout(Duration.ofSeconds(30)).header("Origin", "http://localhost:5173").GET().build();

		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

		assertInternalError(response);
		assertThat(response.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:5173");
		assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).contains("true");
	}

	// 읽을 수 없는 세션의 원인이 JVM 오류면 TolerantSessionSerializer가 Error를 그대로 던진다.
	@Test
	void 필터에서_난_Error도_500_Problem_Details로_응답한다() throws Exception {
		HttpResponse<String> response = get("/api/v1/test-filter/error");

		assertInternalError(response);
	}

	// Spring Security의 방화벽은 수상한 주소(연속된 슬래시 등)를 필터 단계에서 거부한다.
	@Test
	void 방화벽이_거부한_주소는_400_Problem_Details로_응답한다() throws Exception {
		HttpResponse<String> response = get("/api/v1/auth//me");

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
		assertThat(response.body()).contains("\"code\":\"COMMON_BAD_REQUEST\"").contains("\"status\":400")
				.contains("\"instance\":\"/api/v1/auth//me\"");
	}

	// 필터 단계 오류는 응답을 비우고 다시 쓴다. 그 뒤에도 요청 ID가 헤더와 본문에 있어야 로그와 이어 볼 수 있다.
	@Test
	void 필터에서_난_오류의_응답에도_요청_ID가_있다() throws Exception {
		HttpResponse<String> response = get("/api/v1/test-filter/exception");

		assertInternalError(response);
		assertSameRequestIdInHeaderAndBody(response);
	}

	// 세션 저장소 장애처럼 요청 ID가 가장 필요한 순간의 로그다.
	// 요청 ID를 정하는 필터가 오류를 잡는 필터보다 안쪽이면, 오류를 잡는 시점에는 로그 문맥이 이미 비워져 있어 ID가 찍히지 않는다.
	@Test
	void 필터에서_난_오류를_남기는_로그에도_요청_ID가_찍힌다(CapturedOutput output) throws Exception {
		HttpResponse<String> response = get("/api/v1/test-filter/exception");

		String id = response.headers().firstValue("X-Request-Id").orElseThrow();
		assertThat(output.getOut().lines().filter(line -> line.contains("서버 오류 응답")))
				.isNotEmpty()
				.allSatisfy(line -> assertThat(line).contains("[" + id + "|"));
	}

	// 오류 경로는 처음의 필터들이 다 끝난 뒤에 다시 들어온다. 그때 로그 문맥을 다시 채우지 않으면
	// 오류 경로에서 남기는 로그가 어느 요청의 것인지 알 수 없다. 응답의 헤더와 본문만 봐서는 이것이 드러나지 않는다.
	@Test
	void 오류_경로에서_남기는_로그에도_처음의_요청_ID가_찍힌다(CapturedOutput output) throws Exception {
		HttpResponse<String> response = get("/api/v1/test-filter/send-error");

		assertThat(response.statusCode()).isEqualTo(503);
		assertSameRequestIdInHeaderAndBody(response);
		String id = response.headers().firstValue("X-Request-Id").orElseThrow();
		assertThat(output.getOut().lines().filter(line -> line.contains("오류 경로로 온 서버 오류")))
				.isNotEmpty()
				.allSatisfy(line -> assertThat(line).contains("[" + id + "|"));
	}

	// 방화벽은 sendError로 끝내고, 컨테이너가 오류 경로로 다시 보낸다. 다시 들어온 요청도 처음의 ID를 써야 한다.
	@Test
	void 오류_경로로_넘어간_응답에도_처음의_요청_ID가_있다() throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth//me"))
				.timeout(Duration.ofSeconds(30)).header("X-Request-Id", "sent-by-proxy-0123456789").GET().build();

		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.headers().allValues("X-Request-Id")).containsExactly("sent-by-proxy-0123456789");
		assertThat(response.body()).contains("\"requestId\":\"sent-by-proxy-0123456789\"");
	}

	// 오류 경로의 인증을 풀어 준 것은 컨테이너가 다시 보낸 요청뿐이다. 밖에서 직접 부르면 다른 주소와 똑같이 인증을 요구한다.
	@Test
	void 오류_경로를_직접_부르면_인증을_요구한다() throws Exception {
		HttpResponse<String> response = get("/error");

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.body()).contains("\"code\":\"COMMON_UNAUTHENTICATED\"");
	}

	private static void assertInternalError(HttpResponse<String> response) {
		assertThat(response.statusCode()).isEqualTo(500);
		assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
		assertThat(response.body()).contains("\"code\":\"COMMON_INTERNAL_ERROR\"").contains("\"status\":500")
				.doesNotContain("secret-host").doesNotContain("Exception").doesNotContain("trace");
	}

	private static void assertSameRequestIdInHeaderAndBody(HttpResponse<String> response) {
		assertThat(response.headers().allValues("X-Request-Id")).hasSize(1);
		String id = response.headers().firstValue("X-Request-Id").orElseThrow();
		assertThat(id).matches("[0-9a-f]{32}");
		assertThat(response.body()).contains("\"requestId\":\"" + id + "\"");
	}

	private HttpResponse<String> get(String path) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.timeout(Duration.ofSeconds(30)).GET().build();
		return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FailingFilterConfig {

		@Bean
		FailingFilter failingFilter() {
			return new FailingFilter();
		}

	}

	// 세션 필터(가장 앞 + 50)보다 뒤, 보안 필터(-100)보다 앞에서 실패한다. 세션 저장소 장애가 나는 자리와 같다.
	static class FailingFilter extends OncePerRequestFilter implements Ordered {

		@Override
		public int getOrder() {
			return Ordered.HIGHEST_PRECEDENCE + 60;
		}

		@Override
		protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
				throws ServletException, IOException {
			if (request.getRequestURI().endsWith("/test-filter/exception")) {
				throw new IllegalStateException(SECRET);
			}
			if (request.getRequestURI().endsWith("/test-filter/error")) {
				throw new NoClassDefFoundError(SECRET);
			}
			// 필터가 응답을 직접 끝내는 경우. 컨테이너가 오류 경로로 다시 보낸다.
			if (request.getRequestURI().endsWith("/test-filter/send-error")) {
				response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
				return;
			}
			chain.doFilter(request, response);
		}

	}

}
