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
import com.jey.WithJeyUser;
import com.jey.core.audit.AuditLogService;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.PhoneNumber;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import static com.jey.core.member.MemberFixtures.DONGTAN;
import static com.jey.core.member.MemberFixtures.father;
import static com.jey.core.member.MemberFixtures.mother;
import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.core.member.MemberFixtures.registration;
import static com.jey.core.member.MemberFixtures.uniqueName;
import static org.assertj.core.api.Assertions.assertThat;

// 거부된 시도의 기록은 요청과 따로 커밋된다. 요청이 DB 연결을 쥔 채 기록용 연결을 하나 더 얻으려 하면,
// 거부될 요청이 연결 풀 크기(기본 10)만큼 동시에 왔을 때 모두 두 번째 연결을 기다리며 멈춘다.
// 가린 전화번호를 대입으로 알아내려는 스크립트가 바로 이런 요청을 한꺼번에 보낸다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class DeniedAttemptConcurrencyTest {

	// 연결 풀보다 많아야 한다.
	private static final int THREADS = 24;

	private static final long STAFF = 5151;

	@Autowired
	StudentService studentService;

	@Autowired
	GuardianService guardianService;

	@Autowired
	AuditLogService auditLogService;

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = STAFF)
	void 다른_학부모의_번호로_바꾸려는_요청이_한꺼번에_와도_멈추지_않고_모두_기록된다() throws Exception {
		PhoneNumber othersPhone = randomPhone();
		studentService.register(registration(uniqueName(), mother(othersPhone)));
		long guardianId = studentService.register(registration(uniqueName(), mother(randomPhone())))
				.guardians()
				.getFirst()
				.id();

		List<Object> results = runTogether(() -> guardianService.update(guardianId, "김어머니", othersPhone));

		assertAllRejected(results, MemberErrorCode.GUARDIAN_PHONE_DUPLICATED);
		assertThat(auditLogService.search(STAFF, MemberAuditAction.GUARDIAN_PHONE_DUPLICATED.code(), null, MemberAuditAction.GUARDIAN,
				guardianId, null, null, 0, 100).getTotalElements()).isEqualTo(THREADS);
	}

	// 학생 행을 잠근 요청이 기록용 연결을 기다리는 동안, 잠금을 기다리는 나머지 요청이 연결을 모두 쥐고 있게 된다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = STAFF)
	void 이미_붙은_학부모를_붙이려는_요청이_한꺼번에_와도_멈추지_않고_모두_기록된다() throws Exception {
		PhoneNumber phone = randomPhone();
		StudentView student = studentService.register(registration(uniqueName(), mother(phone)));

		List<Object> results = runTogether(() -> studentService.addGuardian(student.id(), father(phone)));

		assertAllRejected(results, MemberErrorCode.GUARDIAN_ALREADY_LINKED);
		assertThat(auditLogService.search(STAFF, MemberAuditAction.GUARDIAN_ALREADY_LINKED.code(), null, MemberAuditAction.STUDENT,
				student.id(), null, null, 0, 100).getTotalElements()).isEqualTo(THREADS);
	}

	private static void assertAllRejected(List<Object> results, MemberErrorCode expected) {
		assertThat(results).hasSize(THREADS)
				.allSatisfy(result -> assertThat(result).isInstanceOfSatisfying(BusinessException.class,
						ex -> assertThat(ex.getErrorCode()).isEqualTo(expected)));
	}

	// 성공한 호출은 돌려준 값을, 실패한 호출은 예외를 담는다. 로그인 정보는 스레드마다 따로라서 넘겨 준다.
	private static List<Object> runTogether(Callable<Object> task) throws Exception {
		SecurityContext login = SecurityContextHolder.getContext();
		ExecutorService executor = Executors.newFixedThreadPool(THREADS);
		CountDownLatch ready = new CountDownLatch(THREADS);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<Object>> futures = new ArrayList<>();
			for (int i = 0; i < THREADS; i++) {
				futures.add(executor.submit(() -> {
					SecurityContextHolder.setContext(login);
					ready.countDown();
					start.await();
					try {
						return task.call();
					}
					catch (Exception ex) {
						return ex;
					}
					finally {
						SecurityContextHolder.clearContext();
					}
				}));
			}
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			List<Object> results = new ArrayList<>();
			for (Future<Object> future : futures) {
				// 연결을 기다리며 멈추면 30초 뒤에야 끝난다. 그 전에 실패로 본다.
				results.add(future.get(15, TimeUnit.SECONDS));
			}
			return results;
		}
		finally {
			executor.shutdownNow();
		}
	}

}
