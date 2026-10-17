package com.jey.loadtest;

import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.jey.core.auth.AccountService;
import com.jey.core.auth.NewAccount;
import com.jey.core.auth.api.UserRole;
import com.jey.dongtanpass.application.PartnerService;
import com.jey.dongtanpass.application.RedeemLocking;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.RedemptionRepository;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * 부하 시험용 앱의 설정({@code loadtest} 프로필에서만). 평소 테스트 컨텍스트에는 끼지 않는다.
 *
 * <ul>
 * <li>{@code jey.loadtest.redeem-locking}으로 사용 처리의 중복 사용 방지 방식을 고른다. {@code optimistic}이면 운영 방식 그대로다.</li>
 * <li>시작할 때 실험 계정을 만든다(직원, 연계 학원과 그 계정). DB는 띄울 때마다 새것이다(Testcontainers).</li>
 * <li>DB 연결 풀의 활성·대기 수를 1초마다 읽어 최대값을 30초마다, 그리고 끌 때 로그로 남긴다.</li>
 * </ul>
 */
@TestConfiguration(proxyBeanMethods = false)
@Profile("loadtest")
class LoadtestConfiguration {

	static final String LOCKING = "jey.loadtest.redeem-locking";

	private static final Set<String> LOCKINGS = Set.of("optimistic", "pessimistic", "unique-first", "redis");

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

	// 어떤 방식으로 떴는지 앱 터미널 첫 화면에 남긴다(캡처용). 모르는 값이면 뜨지 않는다.
	@Bean
	ApplicationRunner loadtestSetup(@Value("${" + LOCKING + ":optimistic}") String locking, RedeemLocking active,
			AccountService accounts, PartnerService partners, LoadtestProperties properties) {
		return args -> {
			if (!LOCKINGS.contains(locking)) {
				throw new IllegalStateException(LOCKING + "는 " + LOCKINGS + " 중 하나여야 한다: " + locking);
			}
			accounts.create(new NewAccount(properties.staffLoginId(), "부하시험 직원", properties.password(),
					UserRole.STAFF, 1L));
			long partnerId = partners.create("부하시험학원").id();
			partners.createAccount(partnerId, properties.partnerLoginId(), "부하시험 연계", properties.password());
			log.info("loadtest ready: redeem-locking={} ({})", locking, active.getClass().getSimpleName());
		};
	}

	@Bean(destroyMethod = "shutdownNow")
	ScheduledExecutorService loadtestPoolSampler(HikariDataSource dataSource) {
		AtomicInteger maxActive = new AtomicInteger();
		AtomicInteger maxPending = new AtomicInteger();
		ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
		sampler.scheduleAtFixedRate(() -> {
			HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
			if (pool != null) {
				maxActive.accumulateAndGet(pool.getActiveConnections(), Math::max);
				maxPending.accumulateAndGet(pool.getThreadsAwaitingConnection(), Math::max);
			}
		}, 1, 1, TimeUnit.SECONDS);
		sampler.scheduleAtFixedRate(() -> log.info("loadtest pool max active={} pending={} (size {})", maxActive.get(),
				maxPending.get(), dataSource.getMaximumPoolSize()), 30, 30, TimeUnit.SECONDS);
		Runtime.getRuntime()
			.addShutdownHook(new Thread(() -> log.info("loadtest pool max active={} pending={} (size {}) [final]",
					maxActive.get(), maxPending.get(), dataSource.getMaximumPoolSize())));
		return sampler;
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
