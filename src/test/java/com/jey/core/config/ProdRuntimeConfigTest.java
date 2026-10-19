package com.jey.core.config;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

// 운영에서만 켜는 연결 설정. 테스트 컨텍스트는 운영 프로필로 뜨지 않으므로 설정 파일의 값을 읽어 본다.
class ProdRuntimeConfigTest {

	@Test
	void 운영_프로필은_Redis에_TLS로_붙는다() throws Exception {
		// ElastiCache는 전송 암호화를 켜 두어 TLS가 아니면 연결을 받지 않는다.
		assertThat(prodProperty("spring.data.redis.ssl.enabled")).isEqualTo(true);
	}

	@Test
	void 운영_프로필은_Redis_설정을_앱에서_바꾸지_않는다() throws Exception {
		// ElastiCache는 CONFIG 명령을 막는다. 앱이 시작할 때 키 만료 알림을 켜려고 CONFIG SET을 보내면 서버가 뜨지 않는다.
		// 그 알림은 ElastiCache 파라미터 그룹에서 켠다(notify-keyspace-events).
		assertThat(prodProperty("spring.session.data.redis.configure-action")).isEqualTo("none");
	}

	@Test
	void 프록시로_믿는_주소는_VPC와_도커와_루프백뿐이다() throws Exception {
		// 로그인 시도 한도와 패스 다시 연결이 클라이언트 IP로 센다. 앱에 접속하는 것은 Nginx 컨테이너(Docker 브리지)다.
		// 좁아서 Nginx를 못 믿으면 모든 요청이 Nginx 주소 하나로 보이고, 넓으면 바깥 주소에서 온 요청의
		// X-Forwarded-For까지 믿게 된다. VPC 대역(10.0.x)은 Nginx를 거치지 않고 VPC 안에서 닿는 경로를 위해 둔다.
		// 실제로 어떤 값이 실려 오는지는 NginxConfigTest가 본다.
		Pattern trusted = Pattern.compile((String) prodProperty("server.tomcat.remoteip.internal-proxies"));

		assertThat(List.of("10.0.10.17", "10.0.0.7", "172.18.0.3", "127.0.0.1"))
				.allMatch(ip -> trusted.matcher(ip).matches());
		assertThat(List.of("203.0.113.5", "10.1.0.1", "192.168.0.1", "172.32.0.1", "100.64.0.1", "10.0.10.2355"))
				.noneMatch(ip -> trusted.matcher(ip).matches());
	}

	private static Object prodProperty(String name) throws Exception {
		List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("application-prod",
				new ClassPathResource("application-prod.yaml"));
		return sources.stream()
				.map(source -> source.getProperty(name))
				.filter(value -> value != null)
				.findFirst()
				.orElse(null);
	}

}
