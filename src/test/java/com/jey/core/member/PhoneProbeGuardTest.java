package com.jey.core.member;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.jey.core.audit.api.AuditEntry;
import com.jey.core.audit.api.AuditLog;
import com.jey.core.auth.api.AccountRateLimiter;
import com.jey.core.auth.api.CurrentActor;
import com.jey.core.auth.api.RateLimit;
import com.jey.core.shared.Actor;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.CommonErrorCode;
import com.jey.core.shared.ErrorCode;
import com.jey.core.shared.RateLimitedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 가드가 무엇을 세고 무엇을 되돌리는지를 가짜 장치로 본다. 다른 테스트는 한도를 크게 잡고 돌아서 이것을 보지 못한다.
class PhoneProbeGuardTest {

	private static final String CHANGE = "member.phone-change";

	private static final String PROBE = "member.phone-probe";

	private final FakeLimiter limiter = new FakeLimiter();

	private final List<AuditEntry> audit = new ArrayList<>();

	private Actor actor = Actor.user(77);

	private final PhoneProbeGuard guard = new PhoneProbeGuard(limiter, limits(), () -> actor, audit::add);

	@Test
	void 성공하면_거부의_횟수만_되돌린다() {
		assertThat(guard.guard(() -> "등록됨")).isEqualTo("등록됨");

		// 변경 요청의 한도를 먼저 세고, 그것은 성공해도 되돌리지 않는다. 틀린 대입은 성공으로 끝나기 때문이다.
		assertThat(limiter.acquired).containsExactly(CHANGE, PROBE);
		assertThat(limiter.refunded).containsExactly(PROBE);
	}

	@ParameterizedTest
	@EnumSource(value = MemberErrorCode.class,
			names = { "STUDENT_DUPLICATED", "GUARDIAN_PHONE_DUPLICATED", "GUARDIAN_ALREADY_LINKED" })
	void 번호가_드러나는_거부는_되돌리지_않는다(MemberErrorCode code) {
		assertThatThrownBy(() -> guard.guard(() -> {
			throw new BusinessException(code);
		})).isInstanceOf(BusinessException.class);

		assertThat(limiter.refunded).isEmpty();
	}

	// 번호가 드러나는 거부는 이 셋뿐이다. 새 에러 코드를 넣으면 MemberErrorCode#revealsExistingPhone에서 정해야 컴파일된다.
	@ParameterizedTest
	@EnumSource(value = MemberErrorCode.class, mode = EnumSource.Mode.EXCLUDE,
			names = { "STUDENT_DUPLICATED", "GUARDIAN_PHONE_DUPLICATED", "GUARDIAN_ALREADY_LINKED" })
	void 그_밖의_업무_오류는_되돌린다(MemberErrorCode code) {
		assertThat(code.revealsExistingPhone()).isFalse();
		ErrorCode thrown = (code.status().value() == 429) ? MemberErrorCode.STUDENT_NOT_FOUND : code;

		assertThatThrownBy(() -> guard.guard(() -> {
			throw new BusinessException(thrown);
		})).isInstanceOf(BusinessException.class);

		assertThat(limiter.refunded).containsExactly(PROBE);
	}

	@Test
	void 다른_모듈의_업무_오류도_되돌린다() {
		assertThatThrownBy(() -> guard.guard(() -> {
			throw new BusinessException(CommonErrorCode.BAD_REQUEST);
		})).isInstanceOf(BusinessException.class);

		assertThat(limiter.refunded).containsExactly(PROBE);
	}

	// 번호가 드러나는 거부를 기록하다 실패하면 그 거부는 서버 오류로 바뀌어 나간다. 그 오류는 맞는 번호에서만 나온다.
	// 되돌리면 한도에 걸리지 않고 번호를 확인하는 길이 된다.
	@Test
	void 예상하지_못한_오류는_되돌리지_않는다() {
		IllegalStateException failure = new IllegalStateException("감사 로그를 쓰지 못했다");

		assertThatThrownBy(() -> guard.guard(() -> {
			throw failure;
		})).isSameAs(failure);

		assertThat(limiter.refunded).isEmpty();
	}

	@Test
	void 변경_요청의_한도에_막히면_일을_하지_않고_막힌_사실을_적는다() {
		limiter.blockOn = CHANGE;
		AtomicInteger worked = new AtomicInteger();

		assertThatThrownBy(() -> guard.guard(worked::incrementAndGet)).isInstanceOfSatisfying(
				RateLimitedException.class,
				ex -> assertThat(ex.getErrorCode()).isEqualTo(MemberErrorCode.PHONE_CHANGE_LIMITED));

		assertThat(worked).hasValue(0);
		assertThat(limiter.acquired).isEmpty();
		assertThat(audit).extracting(entry -> entry.action().code()).containsExactly("member.phone-change-limited");
	}

	// 어느 한도에 막혔는지는 응답으로 가르지 않는다. 감사 로그에서만 가른다.
	@Test
	void 거부의_한도에_막히면_일을_하지_않고_막힌_사실을_적는다() {
		limiter.blockOn = PROBE;
		AtomicInteger worked = new AtomicInteger();

		assertThatThrownBy(() -> guard.guard(worked::incrementAndGet)).isInstanceOfSatisfying(
				RateLimitedException.class,
				ex -> assertThat(ex.getErrorCode()).isEqualTo(MemberErrorCode.PHONE_CHANGE_LIMITED));

		assertThat(worked).hasValue(0);
		assertThat(audit).extracting(entry -> entry.action().code()).containsExactly("member.phone-probe-limited");
	}

	// "Redis가 안 될 때 업무가 멈추지 않게" 통과시키면 한도가 조용히 꺼진다.
	@Test
	void 횟수를_세지_못하면_일을_하지_않는다() {
		limiter.acquireFailure = new IllegalStateException("Redis에 닿지 못했다");
		AtomicInteger worked = new AtomicInteger();

		assertThatThrownBy(() -> guard.guard(worked::incrementAndGet)).isSameAs(limiter.acquireFailure);

		assertThat(worked).hasValue(0);
	}

	// 일은 이미 커밋됐다. 여기서 실패로 답하면 화면이 다시 보내고 중복 거부를 받는다.
	@Test
	void 되돌리지_못해도_성공한_일의_결과를_그대로_돌려준다() {
		limiter.refundFailure = new IllegalStateException("Redis에 닿지 못했다");

		assertThat(guard.guard(() -> "등록됨")).isEqualTo("등록됨");
	}

	@Test
	void 되돌리지_못해도_원래의_오류가_그대로_나간다() {
		limiter.refundFailure = new IllegalStateException("Redis에 닿지 못했다");
		BusinessException notFound = new BusinessException(MemberErrorCode.STUDENT_NOT_FOUND);

		assertThatThrownBy(() -> guard.guard(() -> {
			throw notFound;
		})).isSameAs(notFound);
	}

	// 로그인 없이 도는 일은 셀 계정이 없다.
	@Test
	void 로그인한_계정이_없으면_세지_않고_일을_한다() {
		actor = Actor.none();

		assertThat(guard.guard(() -> "등록됨")).isEqualTo("등록됨");

		assertThat(limiter.acquired).isEmpty();
		assertThat(limiter.refunded).isEmpty();
	}

	private static MemberLimitProperties limits() {
		return new MemberLimitProperties(new MemberLimitProperties.Limit(Duration.ofHours(1), 60),
				new MemberLimitProperties.Limit(Duration.ofHours(1), 100),
				new MemberLimitProperties.Limit(Duration.ofMinutes(15), 10));
	}

	// 센 것과 되돌린 것을 한도의 이름으로 적어 둔다.
	static final class FakeLimiter implements AccountRateLimiter {

		final List<String> acquired = new ArrayList<>();

		final List<String> refunded = new ArrayList<>();

		String blockOn;

		RuntimeException acquireFailure;

		RuntimeException refundFailure;

		@Override
		public Permit acquire(RateLimit limit, Actor.User account, ErrorCode limitedCode,
				Consumer<RateLimitedException> onFirstBlock) {
			if (acquireFailure != null) {
				throw acquireFailure;
			}
			if (limit.name().equals(blockOn)) {
				RateLimitedException denial = new RateLimitedException(limitedCode, Duration.ofMinutes(5));
				onFirstBlock.accept(denial);
				throw denial;
			}
			acquired.add(limit.name());
			return new Permit(limit, account, "구간");
		}

		@Override
		public void refund(Permit permit) {
			if (refundFailure != null) {
				throw refundFailure;
			}
			refunded.add(permit.limit().name());
		}

	}

}
