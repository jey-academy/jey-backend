package com.jey.core.campus.domain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.jey.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

// 마이그레이션이 넣은 초기 행. 마이그레이션은 한 번 나가면 고칠 수 없어서 여기서 확인한다.
// 이 행들은 다른 테스트도 고정값으로 확인하므로 테스트에서 고치지 않는다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class SeedDataTest {

	@Autowired
	CampusRepository campuses;

	@Autowired
	DiscountTypeRepository discountTypes;

	@Autowired
	Clock clock;

	// 앱은 시각을 한국 시각으로 저장하고 읽는다. 마이그레이션이 DB 세션의 시간대(UTC)로 넣으면 9시간 전으로 읽힌다.
	// 테스트 DB는 실행할 때마다 새로 만들어지므로 초기 행의 시각은 지금 근처여야 한다.
	@Test
	void 초기_행의_생성_시각은_앱이_읽었을_때_지금_근처다() {
		Instant now = clock.instant();

		assertThat(campuses.findAllByOrderByIdAsc()).hasSize(2).allSatisfy(campus -> {
			assertThat(campus.getCreatedAt()).isBetween(now.minus(Duration.ofHours(1)), now);
			assertThat(campus.getUpdatedAt()).isEqualTo(campus.getCreatedAt());
		});
		assertThat(discountTypes.findByCode("SIBLING_5").orElseThrow().getCreatedAt())
				.isBetween(now.minus(Duration.ofHours(1)), now);
		assertThat(discountTypes.findByCode("DONGTAN_PASS").orElseThrow().getCreatedAt())
				.isBetween(now.minus(Duration.ofHours(1)), now);
	}

	// 상품 이름을 바꿨다(ADR-0049). 코드는 청구에 남는 값이라 그대로다.
	@Test
	void 패스_할인의_이름은_제이G패스다() {
		DiscountType pass = discountTypes.findByCode("DONGTAN_PASS").orElseThrow();
		assertThat(pass.getName()).isEqualTo("제이G패스");
		assertThat(pass.getRate()).isEqualByComparingTo("0.05");
		assertThat(pass.isActive()).isTrue();
	}

	// 로그인 없이 넣은 행이라 작성자가 없다.
	@Test
	void 초기_행은_작성자가_없다() {
		assertThat(campuses.findAllByOrderByIdAsc()).allSatisfy(campus -> assertThat(campus.getCreatedBy()).isNull());
	}

}
