package com.jey.core.campus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.jey.TestcontainersConfiguration;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.ErrorCode;
import com.jey.core.shared.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

// 같은 요청이 동시에 오면 "이미 있는지 확인"은 둘 다 통과한다. 그때 막는 것은 DB의 유니크 키뿐이다.
// 순서대로 보내는 테스트는 모두 앞의 확인에서 걸려서, 유니크 키가 없거나 그 오류를 잘못 바꿔도 통과한다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class CampusConcurrencyTest {

	private static final int THREADS = 8;

	@Autowired
	ClassService classService;

	@Autowired
	DiscountTypeService discountTypeService;

	@Test
	void 같은_이름의_반을_동시에_만들면_하나만_생기고_나머지는_중복으로_답한다() throws Exception {
		String name = "반-" + UUID.randomUUID().toString().substring(0, 8);

		List<Object> results = runTogether(() -> classService.create(1, name, Money.of(280_000), Money.ZERO));

		assertOneSuccessAndRestDuplicated(results, CampusErrorCode.CLASS_NAME_DUPLICATED);
		assertThat(classService.allClasses(1L)).filteredOn(each -> each.getName().equals(name)).hasSize(1);
	}

	@Test
	void 같은_코드의_할인_유형을_동시에_만들면_하나만_생기고_나머지는_중복으로_답한다() throws Exception {
		String code = "T_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);

		List<Object> results = runTogether(() -> discountTypeService.create(code, "시험 할인", new BigDecimal("0.03")));

		assertOneSuccessAndRestDuplicated(results, CampusErrorCode.DISCOUNT_CODE_DUPLICATED);
		assertThat(discountTypeService.all()).filteredOn(each -> each.getCode().equals(code)).hasSize(1);
	}

	// 성공한 호출은 돌려준 값을, 실패한 호출은 예외를 담는다.
	private static List<Object> runTogether(Callable<Object> task) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(THREADS);
		CountDownLatch ready = new CountDownLatch(THREADS);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<Object>> futures = new ArrayList<>();
			for (int i = 0; i < THREADS; i++) {
				futures.add(executor.submit(() -> {
					ready.countDown();
					start.await();
					try {
						return task.call();
					}
					catch (Exception ex) {
						return ex;
					}
				}));
			}
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			List<Object> results = new ArrayList<>();
			for (Future<Object> future : futures) {
				results.add(future.get(30, TimeUnit.SECONDS));
			}
			return results;
		}
		finally {
			executor.shutdownNow();
		}
	}

	private static void assertOneSuccessAndRestDuplicated(List<Object> results, ErrorCode duplicated) {
		List<Object> failures = results.stream().filter(Exception.class::isInstance).toList();

		assertThat(results).hasSize(THREADS);
		assertThat(failures).hasSize(THREADS - 1)
				.allSatisfy(failure -> assertThat(failure).isInstanceOf(BusinessException.class)
						.extracting(each -> ((BusinessException) each).getErrorCode())
						.isEqualTo(duplicated));
	}

}
