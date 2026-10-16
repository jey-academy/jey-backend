package com.jey.dongtanpass.application;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// QR 서명 키 설정. 없거나 잘못되면 서버가 뜨지 않는다(ADR-0010).
class QrSigningPropertiesTest {

	static final String KEY_1 = "dGVzdC1vbmx5LWRvbmd0YW5wYXNzLXFyLWtleS0wMDE=";

	static final String KEY_2 = "dGVzdC1vbmx5LWRvbmd0YW5wYXNzLXFyLWtleS0wMDI=";

	@Test
	void 올바른_설정은_받는다() {
		QrSigningProperties properties = new QrSigningProperties(2, Map.of(1, KEY_1, 2, KEY_2));

		assertThat(properties.activeKeyVersion()).isEqualTo(2);
		assertThat(properties.keyBytes(1)).hasSize(32);
		assertThat(properties.keyBytes(9)).isNull();
	}

	@Test
	void 버전은_1_이상이다() {
		assertThatIllegalArgumentException().isThrownBy(() -> new QrSigningProperties(0, Map.of(0, KEY_1)));
	}

	@Test
	void 키가_없으면_받지_않는다() {
		assertThatIllegalArgumentException().isThrownBy(() -> new QrSigningProperties(1, null));
		assertThatIllegalArgumentException().isThrownBy(() -> new QrSigningProperties(1, Map.of()));
		assertThatIllegalArgumentException().isThrownBy(() -> new QrSigningProperties(1, Map.of(1, " ")));
	}

	@Test
	void 지금_버전의_키가_있어야_한다() {
		assertThatIllegalArgumentException().isThrownBy(() -> new QrSigningProperties(2, Map.of(1, KEY_1)));
	}

	// HMAC-SHA256의 출력 길이보다 짧은 키는 추측하기 쉬워진다.
	@Test
	void 키는_Base64로_적은_32바이트_이상이다() {
		String short31 = Base64.getEncoder().encodeToString(new byte[31]);

		assertThatIllegalArgumentException().isThrownBy(() -> new QrSigningProperties(1, Map.of(1, short31)));
		assertThatIllegalArgumentException().isThrownBy(() -> new QrSigningProperties(1, Map.of(1, "not base64!!")));
	}

	// 환경변수가 없으면 "${...}"라는 글자가 그대로 들어온다.
	@Test
	void 환경변수가_없으면_그렇다고_알린다() {
		assertThatIllegalArgumentException().isThrownBy(() -> new QrSigningProperties(1, Map.of(1, "${QR_SIGNING_KEY_1}")))
			.withMessageContaining("환경변수");
	}

	@Test
	void 운영_설정_파일은_키를_환경변수에서_읽는다() throws Exception {
		QrSigningProperties properties = bindProdYaml(Map.of("QR_SIGNING_KEY_1", KEY_1));

		assertThat(properties.activeKeyVersion()).isEqualTo(1);
		assertThat(properties.keyBytes(1)).hasSize(32);
	}

	@Test
	void 운영_설정_파일은_키_환경변수가_없으면_읽히지_않는다() {
		assertThatThrownBy(() -> bindProdYaml(Map.of())).rootCause().hasMessageContaining("환경변수");
	}

	// application-prod.yaml을 읽고, 환경변수 자리는 주어진 값으로 채워서 설정을 만든다.
	private static QrSigningProperties bindProdYaml(Map<String, Object> environmentVariables) throws Exception {
		List<PropertySource<?>> yaml = new YamlPropertySourceLoader().load("prod",
				new ClassPathResource("application-prod.yaml"));
		// 이 PC의 실제 환경변수가 섞이지 않게 빈 환경에서 시작한다.
		var environment = new MockEnvironment();
		environment.getPropertySources().addFirst(new MapPropertySource("test-env", environmentVariables));
		yaml.forEach(environment.getPropertySources()::addLast);
		ConfigurationPropertySources.attach(environment);
		return Binder.get(environment).bind("jey.dongtanpass.qr", QrSigningProperties.class).get();
	}

	// 오류 문구와 toString은 로그에 남는다.
	@Test
	void 키는_오류_문구와_문자열_표현에_나오지_않는다() {
		String short31 = Base64.getEncoder().encodeToString(new byte[31]);

		assertThatIllegalArgumentException().isThrownBy(() -> new QrSigningProperties(1, Map.of(1, short31)))
			.withMessageNotContaining(short31);
		assertThat(new QrSigningProperties(1, Map.of(1, KEY_1)).toString()).doesNotContain(KEY_1);
	}

}
