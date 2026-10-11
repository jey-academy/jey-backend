package com.jey.core.member;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.jey.TestcontainersConfiguration;
import com.jey.core.member.GuardianService.EnsuredGuardian;
import com.jey.core.shared.PhoneNumber;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static com.jey.core.member.MemberFixtures.mother;
import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.core.member.MemberFixtures.registration;
import static com.jey.core.member.MemberFixtures.uniqueName;
import static org.assertj.core.api.Assertions.assertThat;

// 형제 여럿을 동시에 등록하면 "그 번호의 학부모가 있는지 확인"은 모두 통과한다. 그때 학부모가 둘 생기지 않게 막는 것은 유니크 키뿐이다.
// 진 쪽은 오류가 아니라 먼저 만들어진 학부모에 붙어서 성공해야 한다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class StudentRegistrationConcurrencyTest {

	private static final int THREADS = 8;

	@Autowired
	StudentService studentService;

	@Autowired
	GuardianService guardianService;

	@Test
	void 같은_새_번호의_학부모로_여러_학생을_동시에_등록하면_학부모는_한_명이다() throws Exception {
		PhoneNumber phone = randomPhone();

		List<Object> results = runTogether(() -> studentService.register(registration(uniqueName(), mother(phone))));

		assertThat(results).hasSize(THREADS).allSatisfy(result -> assertThat(result).isInstanceOf(StudentView.class));
		assertThat(results).map(result -> ((StudentView) result).guardians().getFirst().id()).containsOnly(
				((StudentView) results.getFirst()).guardians().getFirst().id());
		assertThat(results).map(result -> ((StudentView) result).id()).doesNotHaveDuplicates();
		// 새로 만든 쪽은 하나뿐이다.
		assertThat(results).filteredOn(result -> !((StudentView) result).guardians().getFirst().linkedToExisting())
				.hasSize(1);
		// 형제로 묶인다.
		assertThat(studentService.get(((StudentView) results.getFirst()).id()).siblings()).hasSize(THREADS - 1);
	}

	@Test
	void 같은_번호로_학부모를_동시에_찾거나_만들면_모두_같은_학부모를_받는다() throws Exception {
		PhoneNumber phone = randomPhone();

		List<Object> results = runTogether(() -> guardianService.findOrCreate("김어머니", phone));

		assertThat(results).hasSize(THREADS)
				.allSatisfy(result -> assertThat(result).isInstanceOf(EnsuredGuardian.class));
		assertThat(results).map(result -> ((EnsuredGuardian) result).guardianId()).containsOnly(
				((EnsuredGuardian) results.getFirst()).guardianId());
		assertThat(results).filteredOn(result -> !((EnsuredGuardian) result).existing()).hasSize(1);
	}

	// 성공한 호출은 돌려준 값을, 실패한 호출은 예외를 담는다.
	private static List<Object> runTogether(java.util.concurrent.Callable<Object> task) throws Exception {
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

}
