package com.jey.loadtest;

import java.time.Duration;
import java.util.Map;

import com.jey.core.auth.AccountService;
import com.jey.core.auth.NewAccount;
import com.jey.core.auth.api.UserRole;
import com.jey.dongtanpass.application.PartnerService;
import com.jey.dongtanpass.application.RedeemLocking;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.RedemptionRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 부하 시험용 앱의 설정({@code loadtest} 프로필에서만). 평소 테스트 컨텍스트에는 끼지 않는다.
 *
 * <ul>
 * <li>{@code jey.loadtest.redeem-locking}으로 사용 처리의 중복 사용 방지 방식을 고른다. {@code optimistic}이면 운영 방식 그대로다.
 * 고른 값과 실제로 들어간 구현이 다르면 뜨지 않는다.</li>
 * <li>시작할 때 실험 계정을 만든다(직원, 연계 학원과 그 계정). DB는 띄울 때마다 새것이다(Testcontainers).</li>
 * <li>DB 연결 풀의 활성·대기 수를 재어 5초 구간마다 최대값을 로그로 남긴다({@link PoolSampler}).</li>
 * </ul>
 */
@TestConfiguration(proxyBeanMethods = false)
@Profile("loadtest")
class LoadtestConfiguration {

	static final String LOCKING = "jey.loadtest.redeem-locking";

	// 고르는 값과 그때 들어가야 하는 구현. 조건(@ConditionalOnProperty)의 값을 잘못 적으면 조용히 운영 방식으로 뜨므로 시작할 때 맞춰 본다.
	private static final Map<String, String> EXPECTED_IMPLEMENTATIONS = Map.of("optimistic",
			"OptimisticRedeemLocking", "pessimistic", PessimisticRedeemLocking.class.getSimpleName(), "unique-first",
			UniqueFirstRedeemLocking.class.getSimpleName(), "redis", RedisRedeemLocking.class.getSimpleName());

	private static final Logger log = LoggerFactory.getLogger("loadtest");

	@Bean
	@Primary
	@ConditionalOnProperty(name = LOCKING, havingValue = "pessimistic")
	RedeemLocking pessimisticRedeemLocking(PassRepository passes, RedemptionRepository redemptions) {
		return new PessimisticRedeemLocking(passes, redemptions);
	}

	@Bean
	@Primary
	@ConditionalOnProperty(name = LOCKING, havingValue = "unique-first")
	RedeemLocking uniqueFirstRedeemLocking(PassRepository passes, RedemptionRepository redemptions) {
		return new UniqueFirstRedeemLocking(passes, redemptions);
	}

	// 락의 만료 시간은 사용 처리 한 번(부하 아래에서도 수백 ms 이하)보다 훨씬 길게 둔다. 앱이 죽어도 이 시간이 지나면 풀린다.
	@Bean
	@Primary
	@ConditionalOnProperty(name = LOCKING, havingValue = "redis")
	RedeemLocking redisRedeemLocking(PassRepository passes, RedemptionRepository redemptions,
			StringRedisTemplate redis) {
		return new RedisRedeemLocking(passes, redemptions, redis, Duration.ofSeconds(5));
	}

	// 어떤 방식으로 떴는지 앱 터미널 첫 화면에 남긴다(캡처용). 모르는 값이거나 구현이 맞지 않으면 뜨지 않는다.
	@Bean
	ApplicationRunner loadtestSetup(@Value("${" + LOCKING + ":optimistic}") String locking, RedeemLocking active,
			AccountService accounts, PartnerService partners, LoadtestProperties properties) {
		return args -> {
			String expected = EXPECTED_IMPLEMENTATIONS.get(locking);
			if (expected == null) {
				throw new IllegalStateException(
						LOCKING + "는 " + EXPECTED_IMPLEMENTATIONS.keySet() + " 중 하나여야 한다: " + locking);
			}
			if (!active.getClass().getSimpleName().equals(expected)) {
				throw new IllegalStateException(
						LOCKING + "=" + locking + "인데 들어간 구현이 " + active.getClass().getSimpleName() + "다");
			}
			accounts.create(new NewAccount(properties.staffLoginId(), "부하시험 직원", properties.password(),
					UserRole.STAFF, 1L));
			long partnerId = partners.create("부하시험학원").id();
			partners.createAccount(partnerId, properties.partnerLoginId(), "부하시험 연계", properties.password());
			log.info("loadtest ready: redeem-locking={} ({})", locking, active.getClass().getSimpleName());
		};
	}

	// 실행기를 빈으로 내놓지 않는다. ScheduledExecutorService 빈이 있으면 Spring Boot가 기본 스케줄러를 만들지 않고
	// @Scheduled 작업(이벤트 다시 보내기)이 그 실행기에서 돌아, 실험 앱이 운영과 다른 구성이 된다.
	@Bean
	PoolSampler loadtestPoolSampler(HikariDataSource dataSource) {
		return new PoolSampler(dataSource, Duration.ofSeconds(1), Duration.ofSeconds(5));
	}

	@Bean
	LoadtestProperties loadtestProperties(@Value("${jey.loadtest.staff-login-id}") String staffLoginId,
			@Value("${jey.loadtest.partner-login-id}") String partnerLoginId,
			@Value("${jey.loadtest.password}") String password) {
		return new LoadtestProperties(staffLoginId, partnerLoginId, password);
	}

	record LoadtestProperties(String staffLoginId, String partnerLoginId, String password) {

		@Override
		public String toString() {
			return "LoadtestProperties[staffLoginId=" + staffLoginId + ", partnerLoginId=" + partnerLoginId
					+ ", password=***]";
		}

	}

}
