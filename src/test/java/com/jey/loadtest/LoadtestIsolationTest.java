package com.jey.loadtest;

import com.jey.TestcontainersConfiguration;
import com.jey.dongtanpass.application.RedeemLocking;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

// 부하 시험용 설정과 방식은 loadtest 프로필로 띄운 실험용 앱에만 있다. 평소 테스트 컨텍스트에는 끼지 않는다
// (다른 테스트와 같은 설정이라 컨텍스트를 새로 만들지 않는다).
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class LoadtestIsolationTest {

	@Autowired
	ApplicationContext context;

	@Autowired
	RedeemLocking redeemLocking;

	@Test
	void 평소_테스트에는_운영_방식만_있고_실험_설정은_없다() {
		assertThat(context.getBeansOfType(RedeemLocking.class)).hasSize(1);
		assertThat(redeemLocking.getClass().getSimpleName()).isEqualTo("OptimisticRedeemLocking");
		assertThat(context.getBeansOfType(LoadtestConfiguration.class)).isEmpty();
	}

}
