package com.jey.core.member;

import java.util.ArrayList;
import java.util.List;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.audit.api.AuditEntry;
import com.jey.core.audit.api.AuditLog;
import com.jey.core.auth.api.AccountRateLimiter;
import com.jey.core.auth.api.CurrentActor;
import com.jey.core.auth.api.UserRole;
import com.jey.core.member.PhoneProbeGuardTest.FakeLimiter;
import com.jey.core.member.domain.GuardianRepository;
import com.jey.core.member.domain.StudentGuardianRepository;
import com.jey.core.member.domain.StudentRepository;
import com.jey.core.shared.PhoneNumber;
import com.jey.core.shared.PhoneProtector;
import com.jey.core.shared.RateLimitedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import static com.jey.core.member.MemberFixtures.DONGTAN;
import static com.jey.core.member.MemberFixtures.mother;
import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.core.member.MemberFixtures.registration;
import static com.jey.core.member.MemberFixtures.uniqueName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 전체 번호 보기에서 평소의 요청으로는 지나지 않는 경우들.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class PhoneRevealTest {

	private static final long VIEWER = 9201;

	private static final long BLOCKED_VIEWER = 9202;

	@Autowired
	StudentService studentService;

	@Autowired
	PhoneRevealService phoneRevealService;

	@Autowired
	StudentRepository students;

	@Autowired
	StudentGuardianRepository links;

	@Autowired
	GuardianRepository guardians;

	@Autowired
	PhoneProtector protector;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	AuditLog auditLog;

	@Autowired
	CurrentActor currentActor;

	@Autowired
	AccountRateLimiter limiter;

	@Autowired
	MemberLimitProperties limits;

	// 본 사실을 남기지 못했으면 번호를 주지 않는다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = VIEWER)
	void 기록하지_못하면_번호를_주지_않는다() {
		StudentView student = studentService.register(registration(uniqueName(), mother(randomPhone())));
		AuditLog failing = entry -> {
			throw new IllegalStateException("감사 로그를 쓰지 못했다");
		};

		assertThatThrownBy(
				() -> service(failing, limiter).guardianPhone(student.id(), student.guardians().getFirst().id()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("감사 로그를 쓰지 못했다");
	}

	// "Redis가 안 될 때 업무가 멈추지 않게" 통과시키면 한도 없이 번호가 나간다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = VIEWER)
	void 횟수를_세지_못하면_번호를_주지_않고_봤다고_적지도_않는다() {
		StudentView student = studentService.register(registration(uniqueName(), mother(randomPhone())));
		FakeLimiter down = new FakeLimiter();
		down.acquireFailure = new IllegalStateException("Redis에 닿지 못했다");
		List<AuditEntry> written = new ArrayList<>();

		assertThatThrownBy(
				() -> service(written::add, down).guardianPhone(student.id(), student.guardians().getFirst().id()))
				.isSameAs(down.acquireFailure);

		assertThat(written).isEmpty();
	}

	// 막혔다는 것도 기록하지 못하면 429 대신 그 실패가 나간다. 원래의 거부는 그 실패에 붙어 로그에 남는다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = BLOCKED_VIEWER)
	void 막힌_사실을_기록하지_못하면_그_실패를_던진다() {
		FakeLimiter blocked = new FakeLimiter();
		blocked.blockOn = "member.phone-reveal";
		AuditLog failing = entry -> {
			throw new IllegalStateException("감사 로그를 쓰지 못했다");
		};

		assertThatThrownBy(() -> service(failing, blocked).studentPhone(1)).isInstanceOf(IllegalStateException.class)
				.hasMessage("감사 로그를 쓰지 못했다")
				.satisfies(thrown -> assertThat(thrown.getSuppressed()).hasSize(1)
						.allSatisfy(denial -> assertThat(denial).isInstanceOf(RateLimitedException.class)));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = BLOCKED_VIEWER)
	void 한도에_막히면_대상을_읽지_않고_막힌_사실만_적는다() {
		FakeLimiter blocked = new FakeLimiter();
		blocked.blockOn = "member.phone-reveal";
		List<AuditEntry> written = new ArrayList<>();

		assertThatThrownBy(() -> service(written::add, blocked).studentPhone(1)).isInstanceOfSatisfying(
				RateLimitedException.class,
				ex -> assertThat(ex.getErrorCode()).isEqualTo(MemberErrorCode.PHONE_REVEAL_LIMITED));

		assertThat(written).extracting(entry -> entry.action().code()).containsExactly("member.phone-reveal-limited");
	}

	// 센 것은 되돌리지 않는다. 없는 대상을 찌른 요청도 한 번이다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = VIEWER)
	void 본_횟수는_되돌리지_않는다() {
		StudentView student = studentService.register(registration(uniqueName(), mother(randomPhone())));
		FakeLimiter counting = new FakeLimiter();
		PhoneRevealService service = service(auditLog, counting);

		service.guardianPhone(student.id(), student.guardians().getFirst().id());
		assertThatThrownBy(() -> service.studentPhone(Long.MAX_VALUE)).isInstanceOf(RuntimeException.class);

		assertThat(counting.acquired).containsExactly("member.phone-reveal", "member.phone-reveal");
		assertThat(counting.refunded).isEmpty();
	}

	// 누가 봤는지 모르면 세지도 적지도 못한다. 로그인 없이 도는 작업이 실수로 부르면 멈춘다.
	@Test
	void 로그인한_계정이_없으면_볼_수_없다() {
		assertThatThrownBy(() -> phoneRevealService.studentPhone(1)).isInstanceOf(IllegalStateException.class);
	}

	// 본 기록은 그 일의 트랜잭션 안에서, 막힌 기록은 트랜잭션 밖에서 적는다. 바깥에 트랜잭션이 열려 있으면 둘을 가를 수 없다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = VIEWER)
	void 트랜잭션_안에서는_부를_수_없다() {
		assertThatThrownBy(() -> transaction.executeWithoutResult(status -> phoneRevealService.studentPhone(1)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("트랜잭션 밖에서");
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = VIEWER)
	void 돌려주는_값은_가리지_않은_번호다() {
		PhoneNumber motherPhone = randomPhone();
		StudentView student = studentService.register(registration(uniqueName(), mother(motherPhone)));

		assertThat(phoneRevealService.guardianPhone(student.id(), student.guardians().getFirst().id()))
				.isEqualTo(motherPhone);
	}

	private PhoneRevealService service(AuditLog log, AccountRateLimiter rateLimiter) {
		return new PhoneRevealService(students, links, guardians, protector, transaction, log, currentActor,
				rateLimiter, limits);
	}

}
