package com.jey.core.config;

import com.jey.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 배포 스크립트는 새 슬롯의 readiness가 200이어야 Nginx를 옮긴다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthProbeTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	HealthEndpointGroups groups;

	@Test
	void readiness와_liveness가_200이다() throws Exception {
		mockMvc.perform(get("/actuator/health/readiness"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
		mockMvc.perform(get("/actuator/health/liveness"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	void readiness는_DB와_Redis를_포함한다() {
		// DB·Redis에 닿기 전에 readiness가 UP이면 고장 난 슬롯으로 트래픽을 옮긴다.
		HealthEndpointGroup readiness = groups.get("readiness");

		assertThat(readiness).isNotNull();
		assertThat(readiness.isMember("db")).as("db").isTrue();
		assertThat(readiness.isMember("redis")).as("redis").isTrue();
	}

	@Test
	void liveness는_DB와_Redis를_포함하지_않는다() {
		// liveness에 바깥 의존을 넣으면 DB가 잠깐 멈출 때 멀쩡한 앱을 죽은 것으로 본다.
		HealthEndpointGroup liveness = groups.get("liveness");

		assertThat(liveness).isNotNull();
		assertThat(liveness.isMember("db")).as("db").isFalse();
		assertThat(liveness.isMember("redis")).as("redis").isFalse();
	}

}
