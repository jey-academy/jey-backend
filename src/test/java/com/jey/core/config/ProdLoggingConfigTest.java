package com.jey.core.config;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

// 운영 로그는 JSON 한 줄이어야 로그를 모으는 도구가 요청 ID로 찾을 수 있다 (ADR-0028).
// 값을 잘못 적으면(없는 형식 이름) 서버가 뜨지 않지만, 키를 잘못 적거나 들여쓰기가 틀리면 조용히 글 형식으로 나간다.
// 테스트에서 로그 형식을 실제로 바꾸면 같이 도는 다른 테스트의 출력까지 바뀌므로, 설정 파일의 값만 읽어 본다.
// JSON에 요청 ID가 실리는 것 자체는 Spring Boot의 기능이고, 실제 출력은 서버를 띄워 확인했다.
class ProdLoggingConfigTest {

	@Test
	void 운영_프로필은_로그를_JSON_형식으로_내보내게_설정돼_있다() throws Exception {
		List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("application-prod",
				new ClassPathResource("application-prod.yaml"));

		Object format = sources.stream()
				.map(source -> source.getProperty("logging.structured.format.console"))
				.filter(value -> value != null)
				.findFirst()
				.orElse(null);

		assertThat(format).as("application-prod.yaml의 logging.structured.format.console").isEqualTo("logstash");
	}

}
