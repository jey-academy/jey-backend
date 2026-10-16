package com.jey.dongtanpass.domain;

import com.jey.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

// 패스를 받는 지점과 할인은 마이그레이션이 넣는다. 엔티티는 처음 쓰는 기능(학부모 열람, 사용 처리)에서 더한다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class BenefitSeedTest {

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void 패스를_받는_지점과_할인은_첫_행으로_들어_있다() {
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM dongtanpass_benefit WHERE provider_campus_id = 1 AND discount_type_code = 'DONGTAN_PASS'",
				Integer.class))
			.isEqualTo(1);
	}

}
