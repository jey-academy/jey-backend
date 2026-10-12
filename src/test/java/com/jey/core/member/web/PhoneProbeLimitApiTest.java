package com.jey.core.member.web;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.audit.AuditLogService;
import com.jey.core.audit.api.AuditOutcome;
import com.jey.core.audit.domain.AuditRecord;
import com.jey.core.auth.api.UserRole;
import com.jey.core.member.GuardianService;
import com.jey.core.member.MemberErrorCode;
import com.jey.core.member.StudentService;
import com.jey.core.member.StudentView;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.PhoneNumber;
import com.jey.core.shared.RateLimitedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static com.jey.TestCsrf.csrfToken;
import static com.jey.TestIdempotency.idempotencyKey;
import static com.jey.core.member.MemberFixtures.DONGTAN;
import static com.jey.core.member.MemberFixtures.father;
import static com.jey.core.member.MemberFixtures.mother;
import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.core.member.MemberFixtures.registration;
import static com.jey.core.member.MemberFixtures.uniqueName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// "그 번호가 이미 있다"는 답은 맞는 번호를 넣었을 때만 나온다. 한 계정이 그 답을 되풀이해 받으면 가린 번호를 알아낼 수 있다.
// 그 답을 한도만큼 받은 계정은 풀릴 때까지 전화번호가 실린 변경을 하지 못한다.
// 테스트 프로필은 한도를 크게 잡아 두었으므로 운영과 같은 값을 여기서 정한다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = { "jey.member.phone-probe-limit.window=15m", "jey.member.phone-probe-limit.max=10" })
// 변경 요청의 한도(phone-change-limit)는 테스트 프로필의 큰 값을 그대로 쓴다. 그 한도는 PhoneChangeLimitApiTest가 본다.
class PhoneProbeLimitApiTest {

	private static final int LIMIT = 10;

	// 테스트마다 다른 계정을 쓴다. 그 계정의 횟수와 기록을 통째로 센다. 감사 로그는 테스트 사이에 남으므로 계정을 같이 쓰면 순서에 따라 깨진다.
	private static final long PROBER = 9301;

	private static final long CAREFUL = 9302;

	private static final long RACER = 9303;

	private static final long BYSTANDER = 9304;

	private static final long BLOCKED_READER = 9305;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	StudentService studentService;

	@Autowired
	GuardianService guardianService;

	@Autowired
	AuditLogService auditLogService;

	@Autowired
	StringRedisTemplate redis;

	@BeforeEach
	void forgetCounts() {
		redis.delete(redis.keys("jey:auth:account-limit:member.phone-*"));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = PROBER)
	void 번호가_드러나는_거부를_한도만큼_받으면_전화번호가_실린_변경이_막힌다() throws Exception {
		PhoneNumber phone = randomPhone();
		PhoneNumber othersPhone = randomPhone();
		String name = uniqueName();
		StudentView student = studentService.register(registration(name, mother(phone)));
		long guardianId = student.guardians().getFirst().id();
		studentService.register(registration(uniqueName(), mother(othersPhone)));

		// 세 가지 거부가 한 한도를 같이 쓴다.
		for (int i = 0; i < 4; i++) {
			registerStudent(name, phone).andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("MEMBER_STUDENT_DUPLICATED"));
		}
		for (int i = 0; i < 3; i++) {
			changeGuardianPhone(guardianId, othersPhone).andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("MEMBER_GUARDIAN_PHONE_DUPLICATED"));
		}
		for (int i = 0; i < 3; i++) {
			addGuardian(student.id(), phone).andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("MEMBER_GUARDIAN_ALREADY_LINKED"));
		}

		// 이제부터는 무엇을 넣어도 같은 답이다. 맞는 번호인지 아닌지를 알 수 없다.
		registerStudent(name, phone).andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("MEMBER_PHONE_CHANGE_LIMITED"))
				// 15분 구간의 남은 시간(초). 방금 시작한 구간이라 거의 다 남아 있다.
				.andExpect(header().string("Retry-After", matchesPattern("(8[5-9]\\d|900)")));
		String blockedName = uniqueName();
		registerStudent(blockedName, randomPhone()).andExpect(status().isTooManyRequests());
		changeGuardianPhone(guardianId, othersPhone).andExpect(status().isTooManyRequests());
		changeGuardianPhone(guardianId, randomPhone()).andExpect(status().isTooManyRequests());
		addGuardian(student.id(), randomPhone()).andExpect(status().isTooManyRequests());

		// 막힌 요청은 일을 하지 않았다. 등록되지 않았고 번호도 바뀌지 않았다.
		assertThat(studentService.search(null, blockedName, null, 0, 10)).isEmpty();
		assertThat(studentService.get(student.id()).guardians()).hasSize(1);
		// 거부 기록은 받은 만큼이고, 막힌 사실은 계속 보내도 구간마다 한 줄이다.
		List<AuditRecord> denied = auditLogService
				.search(PROBER, null, AuditOutcome.DENIED, null, null, null, null, 0, 50)
				.getContent();
		assertThat(denied).filteredOn(each -> each.getAction().equals("member.phone-probe-limited")).hasSize(1);
		assertThat(denied).filteredOn(each -> !each.getAction().equals("member.phone-probe-limited")).hasSize(LIMIT);
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = BLOCKED_READER)
	void 막힌_동안에도_조회와_번호가_없는_수정은_된다() throws Exception {
		PhoneNumber phone = randomPhone();
		String name = uniqueName();
		StudentView student = studentService.register(registration(name, mother(phone)));
		long guardianId = student.guardians().getFirst().id();
		for (int i = 0; i < LIMIT; i++) {
			registerStudent(name, phone).andExpect(status().isConflict());
		}
		registerStudent(name, phone).andExpect(status().isTooManyRequests());

		mockMvc.perform(get("/api/v1/students/{id}", student.id())).andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/students").param("phoneLast4", phone.last4())).andExpect(status().isOk());
		// 번호를 싣지 않은 학부모 수정은 "그 번호가 이미 있다"는 답을 받을 일이 없다.
		send(put("/api/v1/guardians/{id}", guardianId), "{\"name\": \"바뀐 이름\"}").andExpect(status().isOk());
	}

	// 정상 업무가 한도를 깎으면 바쁜 날의 데스크가 막힌다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = CAREFUL)
	void 성공한_요청과_다른_오류는_횟수에_들지_않는다() throws Exception {
		StudentView student = studentService.register(registration(uniqueName(), mother(randomPhone())));
		long guardianId = student.guardians().getFirst().id();

		for (int i = 0; i < LIMIT * 2; i++) {
			registerStudent(uniqueName(), randomPhone()).andExpect(status().isCreated());
		}
		for (int i = 0; i < LIMIT * 2; i++) {
			// 없는 학생, 형식이 틀린 요청.
			addGuardian(Long.MAX_VALUE, randomPhone()).andExpect(status().isNotFound());
			send(post("/api/v1/students"), "{\"campusId\": 1, \"name\": \"연락처 없음\", \"grade\": \"HIGH_1\", \"guardians\": []}")
					.andExpect(status().isBadRequest());
		}
		changeGuardianPhone(guardianId, randomPhone()).andExpect(status().isOk());

		assertThat(redis.opsForHash().get("jey:auth:account-limit:member.phone-probe:" + CAREFUL, "count"))
				.isEqualTo("0");
	}

	// 스크립트는 요청을 한꺼번에 보낸다. 받은 뒤에 세면 모두 확인을 지나 한도보다 많은 답을 받는다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = RACER)
	void 한꺼번에_보내도_번호가_드러나는_답은_한도만큼만_나간다() throws Exception {
		PhoneNumber othersPhone = randomPhone();
		studentService.register(registration(uniqueName(), mother(othersPhone)));
		long guardianId = studentService.register(registration(uniqueName(), mother(randomPhone())))
				.guardians()
				.getFirst()
				.id();

		List<Object> results = runTogether(24, () -> guardianService.update(guardianId, "김어머니", othersPhone));

		assertThat(results).filteredOn(RateLimitedException.class::isInstance).hasSize(24 - LIMIT);
		assertThat(results).filteredOn(each -> !(each instanceof RateLimitedException))
				.hasSize(LIMIT)
				.allSatisfy(each -> assertThat(each).isInstanceOfSatisfying(BusinessException.class,
						ex -> assertThat(ex.getErrorCode()).isEqualTo(MemberErrorCode.GUARDIAN_PHONE_DUPLICATED)));
		assertThat(auditLogService.search(RACER, "member.phone-probe-limited", null, null, null, null, null, 0, 10)
				.getTotalElements()).isEqualTo(1);
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = BYSTANDER)
	void 다른_계정이_막혀도_이_계정은_영향받지_않는다() throws Exception {
		String othersKey = "jey:auth:account-limit:member.phone-probe:" + PROBER;
		redis.opsForHash().put(othersKey, "count", String.valueOf(LIMIT));
		redis.expire(othersKey, java.time.Duration.ofMinutes(15));

		registerStudent(uniqueName(), randomPhone()).andExpect(status().isCreated());
	}

	private ResultActions registerStudent(String name, PhoneNumber motherPhone) throws Exception {
		return send(post("/api/v1/students"), """
				{"campusId": 1, "name": "%s", "grade": "HIGH_1",
				 "guardians": [{"name": "김어머니", "relation": "MOTHER", "phone": "%s"}]}
				""".formatted(name, motherPhone.value()));
	}

	private ResultActions changeGuardianPhone(long guardianId, PhoneNumber phone) throws Exception {
		return send(put("/api/v1/guardians/{id}", guardianId),
				"{\"name\": \"김어머니\", \"phone\": \"%s\"}".formatted(phone.value()));
	}

	private ResultActions addGuardian(long studentId, PhoneNumber phone) throws Exception {
		return send(post("/api/v1/students/{id}/guardians", studentId),
				"{\"name\": \"김아버지\", \"relation\": \"FATHER\", \"phone\": \"%s\"}".formatted(phone.value()));
	}

	private ResultActions send(MockHttpServletRequestBuilder request, String json) throws Exception {
		return mockMvc.perform(request.with(csrfToken(mockMvc)).with(idempotencyKey()).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	// 성공한 호출은 돌려준 값을, 실패한 호출은 예외를 담는다. 로그인 정보는 스레드마다 따로라서 넘겨 준다.
	private static List<Object> runTogether(int threads, java.util.concurrent.Callable<Object> task) throws Exception {
		SecurityContext login = SecurityContextHolder.getContext();
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<Object>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
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
				results.add(future.get(15, TimeUnit.SECONDS));
			}
			return results;
		}
		finally {
			executor.shutdownNow();
		}
	}

}
