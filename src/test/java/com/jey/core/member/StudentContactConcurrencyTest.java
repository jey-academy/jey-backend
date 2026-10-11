package com.jey.core.member;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.jey.TestcontainersConfiguration;
import com.jey.core.member.domain.Grade;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.PhoneNumber;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static com.jey.core.member.MemberFixtures.DONGTAN;
import static com.jey.core.member.MemberFixtures.father;
import static com.jey.core.member.MemberFixtures.mother;
import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.core.member.MemberFixtures.registration;
import static com.jey.core.member.MemberFixtures.registrationWithPhone;
import static com.jey.core.member.MemberFixtures.uniqueName;
import static org.assertj.core.api.Assertions.assertThat;

// "남은 연락처를 세고 나서 지운다"는 두 요청이 동시에 오면 둘 다 "아직 하나 더 있다"고 본다.
// 학생 행을 잠그지 않으면 연락처가 하나도 없는 학생이 생긴다. 경합은 매번 일어나지 않으므로 여러 번 되풀이한다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class StudentContactConcurrencyTest {

	private static final int ROUNDS = 20;

	@Autowired
	StudentService studentService;

	@Test
	void 학생_번호_지우기와_마지막_학부모_끊기가_동시에_와도_연락처_하나는_남는다() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			StudentView student = studentService
					.register(registrationWithPhone(uniqueName(), randomPhone(), mother(randomPhone())));
			long guardianId = student.guardians().getFirst().id();

			List<Object> results = runTogether(() -> {
				studentService.removePhone(student.id());
				return "phone";
			}, () -> {
				studentService.removeGuardian(student.id(), guardianId);
				return "guardian";
			});

			assertOneRejectedAsLastContact(results);
			StudentView after = studentService.get(student.id());
			assertThat((after.phone() != null ? 1 : 0) + after.guardians().size()).isEqualTo(1);
			assertThat(after.primaryContact()).isNotNull();
		}
	}

	@Test
	void 학부모_둘을_동시에_끊어도_한_명은_남는다() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			StudentView student = studentService
					.register(registration(uniqueName(), mother(randomPhone()), father(randomPhone())));
			long first = student.guardians().getFirst().id();
			long second = student.guardians().getLast().id();

			List<Object> results = runTogether(() -> {
				studentService.removeGuardian(student.id(), first);
				return "first";
			}, () -> {
				studentService.removeGuardian(student.id(), second);
				return "second";
			});

			assertOneRejectedAsLastContact(results);
			assertThat(studentService.get(student.id()).guardians()).hasSize(1);
		}
	}

	// 수정이 학생을 읽은 뒤 번호 지우기가 끝나면, 수정이 읽어 둔 옛 번호를 다시 써서 지운 번호가 되살아난다.
	@Test
	void 번호_지우기와_정보_수정이_동시에_와도_지운_번호가_되살아나지_않는다() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			PhoneNumber phone = randomPhone();
			String name = uniqueName();
			StudentView student = studentService.register(registrationWithPhone(name, phone, mother(randomPhone())));
			StudentChange sameValues = new StudentChange(DONGTAN, null, name, "동탄고", Grade.HIGH_2, student.status(),
					student.registeredAt(), null);

			List<Object> results = runTogether(() -> {
				studentService.removePhone(student.id());
				return "phone";
			}, () -> studentService.update(student.id(), sameValues));

			assertThat(results).noneMatch(Exception.class::isInstance);
			StudentView after = studentService.get(student.id());
			assertThat(after.phone()).isNull();
			assertThat(after.grade()).isEqualTo(Grade.HIGH_2);
		}
	}

	// 삭제 버튼을 두 번 누른 경우다. 뒤에 온 쪽은 서버 오류가 아니라 "그런 학부모가 없다"를 받아야 한다.
	@Test
	void 같은_학부모를_동시에_두_번_끊으면_한쪽은_없는_학부모로_답한다() throws Exception {
		for (int round = 0; round < ROUNDS; round++) {
			StudentView student = studentService
					.register(registrationWithPhone(uniqueName(), randomPhone(), mother(randomPhone())));
			long guardianId = student.guardians().getFirst().id();

			List<Object> results = runTogether(() -> {
				studentService.removeGuardian(student.id(), guardianId);
				return "a";
			}, () -> {
				studentService.removeGuardian(student.id(), guardianId);
				return "b";
			});

			assertThat(results).filteredOn(Exception.class::isInstance)
					.singleElement()
					.isInstanceOfSatisfying(BusinessException.class,
							ex -> assertThat(ex.getErrorCode()).isEqualTo(MemberErrorCode.GUARDIAN_NOT_FOUND));
			assertThat(studentService.get(student.id()).guardians()).isEmpty();
		}
	}

	private static void assertOneRejectedAsLastContact(List<Object> results) {
		assertThat(results).filteredOn(Exception.class::isInstance)
				.singleElement()
				.isInstanceOfSatisfying(BusinessException.class,
						ex -> assertThat(ex.getErrorCode()).isEqualTo(MemberErrorCode.LAST_CONTACT));
	}

	// 성공한 호출은 돌려준 값을, 실패한 호출은 예외를 담는다.
	@SafeVarargs
	private static List<Object> runTogether(Callable<Object>... tasks) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(tasks.length);
		CountDownLatch ready = new CountDownLatch(tasks.length);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<Object>> futures = new ArrayList<>();
			for (Callable<Object> task : tasks) {
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
