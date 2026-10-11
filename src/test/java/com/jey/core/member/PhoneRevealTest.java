package com.jey.core.member;

import java.time.Duration;
import java.util.Optional;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.audit.AuditLogService;
import com.jey.core.audit.api.AuditLog;
import com.jey.core.auth.api.AccountRateLimiter;
import com.jey.core.auth.api.CurrentActor;
import com.jey.core.auth.api.RateLimit;
import com.jey.core.auth.api.UserRole;
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
	AuditLogService auditLogService;

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
		PhoneRevealService service = new PhoneRevealService(students, links, guardians, protector, transaction,
				failing, currentActor, limiter, limits);

		assertThatThrownBy(() -> service.guardianPhone(student.id(), student.guardians().getFirst().id()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("감사 로그를 쓰지 못했다");
	}

	// 막혔다는 것도 기록하지 못하면 429 대신 그 실패가 나간다. 원래의 거부는 그 실패에 붙어 로그에 남는다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN, id = BLOCKED_VIEWER)
	void 막힌_사실을_기록하지_못하면_그_실패를_던진다() {
		AccountRateLimiter alwaysBlocked = new AccountRateLimiter() {
			@Override
			public Optional<Blocked> tryAcquire(RateLimit limit, long accountId) {
				return Optional.of(new Blocked(Duration.ofMinutes(10), true));
			}

			@Override
			public void refund(RateLimit limit, long accountId) {
			}
		};
		AuditLog failing = entry -> {
			throw new IllegalStateException("감사 로그를 쓰지 못했다");
		};
		PhoneRevealService service = new PhoneRevealService(students, links, guardians, protector, transaction,
				failing, currentActor, alwaysBlocked, limits);

		assertThatThrownBy(() -> service.studentPhone(1)).isInstanceOf(IllegalStateException.class)
				.satisfies(thrown -> assertThat(thrown.getSuppressed()).hasSize(1)
						.allSatisfy(denial -> assertThat(denial).isInstanceOf(RateLimitedException.class)));
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

	@Test
	void 한도_설정은_빠지거나_너무_작으면_안_된다() {
		var fine = new MemberLimitProperties.Limit(Duration.ofHours(1), 60);
		assertThatThrownBy(() -> new MemberLimitProperties(null, fine)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("phone-reveal-limit");
		assertThatThrownBy(() -> new MemberLimitProperties(fine, null)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("phone-probe-limit");
		// 단위를 빼먹은 "5"는 5밀리초로 읽힌다.
		assertThatThrownBy(() -> new MemberLimitProperties(new MemberLimitProperties.Limit(Duration.ofMillis(5), 60),
				fine)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("window");
		assertThatThrownBy(() -> new MemberLimitProperties(new MemberLimitProperties.Limit(null, 60), fine))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("window");
		assertThatThrownBy(() -> new MemberLimitProperties(fine, new MemberLimitProperties.Limit(Duration.ofHours(1), 0)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("max");
	}

	@Test
	void 운영_설정의_한도는_정한_값이다() {
		assertThat(limits.phoneReveal()).isEqualTo(new RateLimit("member.phone-reveal", 60, Duration.ofHours(1)));
		assertThat(limits.phoneProbe()).isEqualTo(new RateLimit("member.phone-probe", 10, Duration.ofMinutes(15)));
	}

}
