package com.jey.loadtest;

import com.jey.JeyBackendApplication;
import com.jey.TestcontainersConfiguration;
import org.springframework.boot.SpringApplication;

/**
 * 부하 시험(k6)용으로 앱을 띄운다: {@code ./gradlew loadtestRun -Plocking=pessimistic}. MySQL·Redis는 Testcontainers로 새로 뜬다.
 * 프로필은 {@code test,loadtest}(테스트용 키를 그대로 쓰고, 한도만 넉넉하게 바꾼다). 운영 jar에는 들어가지 않는다.
 */
public final class LoadtestApplication {

	private LoadtestApplication() {
	}

	public static void main(String[] args) {
		SpringApplication.from(JeyBackendApplication::main)
			.with(TestcontainersConfiguration.class, LoadtestConfiguration.class)
			.run(args);
	}

}
