package com.jey.infra;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import static org.assertj.core.api.Assertions.assertThat;

// 운영의 Nginx 설정(infra/nginx/conf.d/app.conf)을 실제 nginx로 띄우고, 앱 자리에 받은 헤더를 그대로 돌려주는 nginx를 둔다.
// 앱은 X-Forwarded-For의 값을 클라이언트 IP로 믿는다(로그인 시도 한도, 패스 다시 연결). 그 값을 이 설정이 정한다.
@Testcontainers
class NginxConfigTest {

	private static final DockerImageName NGINX = DockerImageName.parse("nginx:1.28");

	private static final String ECHO_CONF = """
			server {
				listen 8080;
				location / {
					default_type text/plain;
					return 200 "xff=[$http_x_forwarded_for] proto=[$http_x_forwarded_proto] xfhost=[$http_x_forwarded_host] xfport=[$http_x_forwarded_port] fwd=[$http_forwarded] uri=[$request_uri]";
				}
			}
			""";

	private static final Network NETWORK = Network.newNetwork();

	@Container
	private static final GenericContainer<?> APP = new GenericContainer<>(NGINX)
			.withNetwork(NETWORK)
			.withNetworkAliases("app-echo")
			.withCopyToContainer(Transferable.of(ECHO_CONF), "/etc/nginx/conf.d/default.conf");

	@Container
	private static final GenericContainer<?> FRONT = new GenericContainer<>(NGINX)
			.withNetwork(NETWORK)
			.dependsOn(APP)
			.withExposedPorts(80)
			.withCopyFileToContainer(MountableFile.forHostPath(Path.of("infra/nginx/conf.d/app.conf")),
					"/etc/nginx/conf.d/default.conf")
			.withCopyToContainer(Transferable.of("server app-echo:8080;\n"), "/etc/nginx/upstream/active.conf");

	private static final HttpClient HTTP = HttpClient.newHttpClient();

	@Test
	void 클라이언트_IP는_CloudFront_Viewer_Address에서_IP만_떼고_요청이_보낸_X_Forwarded_For는_버린다() throws Exception {
		String echoed = get("/api/x", "CloudFront-Viewer-Address", "203.0.113.5:51234", "X-Forwarded-For", "6.6.6.6");

		assertThat(echoed).contains("xff=[203.0.113.5]");
	}

	@Test
	void IPv6_주소도_끝의_포트만_뗀다() throws Exception {
		assertThat(get("/api/x", "CloudFront-Viewer-Address", "2001:db8::1:443")).contains("xff=[2001:db8::1]");
	}

	@Test
	void CloudFront를_거치지_않은_요청에는_X_Forwarded_For를_싣지_않는다() throws Exception {
		// 앱은 접속 주소(Nginx)를 쓴다. 요청이 보낸 값이 그대로 넘어가면 아무 IP나 적어 한도를 피할 수 있다.
		assertThat(get("/api/x", "X-Forwarded-For", "6.6.6.6")).contains("xff=[]");
	}

	@Test
	void 요청이_보낸_호스트_포트_전달_헤더는_앱에_닿지_않는다() throws Exception {
		// Tomcat의 remoteip는 X-Forwarded-Host·Port도 읽는다. 요청자가 앱이 보는 서버 이름·포트를 바꾸지 못하게 비운다.
		String echoed = get("/api/x", "X-Forwarded-Host", "evil.example", "X-Forwarded-Port", "8443", "Forwarded",
				"for=6.6.6.6;host=evil.example");

		assertThat(echoed).contains("xfhost=[]", "xfport=[]", "fwd=[]");
	}

	@Test
	void 원래_프로토콜은_CloudFront_Forwarded_Proto에서_https만_https로_옮긴다() throws Exception {
		assertThat(get("/api/x", "CloudFront-Forwarded-Proto", "https")).contains("proto=[https]");
		assertThat(get("/api/x", "CloudFront-Forwarded-Proto", "gopher", "X-Forwarded-Proto", "https"))
				.contains("proto=[http]");
	}

	@Test
	void api_health는_앱의_readiness로_넘기고_actuator는_바깥에_내지_않는다() throws Exception {
		assertThat(get("/api/health")).contains("uri=[/actuator/health/readiness]");
		assertThat(status("/actuator/health")).isEqualTo(404);
		assertThat(status("/")).isEqualTo(404);
	}

	@Test
	void api_ping은_앱_없이_Nginx가_답한다() throws Exception {
		HttpResponse<String> response = send("/api/ping");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).isEqualTo("ok\n");
	}

	private static String get(String path, String... headers) throws Exception {
		HttpResponse<String> response = send(path, headers);
		assertThat(response.statusCode()).isEqualTo(200);
		return response.body();
	}

	private static int status(String path) throws Exception {
		return send(path).statusCode();
	}

	private static HttpResponse<String> send(String path, String... headers) throws Exception {
		HttpRequest.Builder request = HttpRequest
				.newBuilder(URI.create("http://" + FRONT.getHost() + ":" + FRONT.getMappedPort(80) + path));
		if (headers.length > 0) {
			request.headers(headers);
		}
		return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

}
