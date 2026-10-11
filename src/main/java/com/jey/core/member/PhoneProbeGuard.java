package com.jey.core.member;

import java.util.Set;
import java.util.function.Supplier;

import com.jey.core.audit.api.AuditLog;
import com.jey.core.auth.api.AccountRateLimiter;
import com.jey.core.auth.api.CurrentActor;
import com.jey.core.auth.api.RateLimit;
import com.jey.core.shared.Actor;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.ErrorCode;
import com.jey.core.shared.RateLimitedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 전화번호가 실린 변경 요청을 감싸서, "그 번호가 이미 있다"는 답을 한 계정이 되풀이해 받지 못하게 한다(ADR-0026).
 *
 * <p>그런 답은 맞는 번호를 넣었을 때만 나온다. 가린 번호의 가운데 자리를 바꿔 가며 넣으면 번호를 알아낼 수 있다(ADR-0023).
 * 계정마다 그런 답을 받은 횟수를 세고, 한도에 닿으면 풀릴 때까지 이 길을 지나는 요청을 모두 막는다. 맞는 번호를 넣은 요청도 막힌다.
 *
 * <p>일을 하기 전에 먼저 세고, 그런 답으로 끝나지 않았으면 되돌린다. 답을 받은 뒤에 세면 동시에 보낸 요청이 모두 확인을 통과한다.
 * 트랜잭션 밖에서 부른다. 막힌 사실은 트랜잭션 밖에서 적어야 한다.
 */
@Component
class PhoneProbeGuard {

	private static final Logger log = LoggerFactory.getLogger(PhoneProbeGuard.class);

	// 응답만으로 "그 번호가 있다"를 알 수 있는 거부들.
	private static final Set<ErrorCode> REVEALING_DENIALS = Set.of(MemberErrorCode.STUDENT_DUPLICATED,
			MemberErrorCode.GUARDIAN_PHONE_DUPLICATED, MemberErrorCode.GUARDIAN_ALREADY_LINKED);

	private final AccountRateLimiter limiter;

	private final RateLimit limit;

	private final CurrentActor currentActor;

	private final AuditLog auditLog;

	PhoneProbeGuard(AccountRateLimiter limiter, MemberLimitProperties limits, CurrentActor currentActor,
			AuditLog auditLog) {
		this.limiter = limiter;
		this.limit = limits.phoneProbe();
		this.currentActor = currentActor;
		this.auditLog = auditLog;
	}

	/**
	 * @param work 전화번호가 실린 변경. 번호가 드러나는 거부는 {@link BusinessException}으로 던진다
	 * @throws RateLimitedException 이 계정이 그런 거부를 한도만큼 받아 막혀 있을 때. {@code work}는 실행되지 않는다
	 */
	<T> T guard(Supplier<T> work) {
		// 로그인 없이 도는 일(초기 자료 넣기 등)은 셀 계정이 없다. 응답을 받아 볼 사람도 없다.
		if (!(currentActor.get() instanceof Actor.User requester)) {
			return work.get();
		}
		long accountId = requester.accountId();
		limiter.tryAcquire(limit, accountId).ifPresent(blocked -> {
			throw LimitBlocks.rejection(limiter, auditLog, limit, accountId, blocked,
					MemberAuditAction.PHONE_PROBE_LIMITED, MemberErrorCode.PHONE_CHANGE_LIMITED);
		});
		T result;
		try {
			result = work.get();
		}
		catch (BusinessException ex) {
			if (!REVEALING_DENIALS.contains(ex.getErrorCode())) {
				refund(accountId);
			}
			throw ex;
		}
		catch (RuntimeException ex) {
			refund(accountId);
			throw ex;
		}
		refund(accountId);
		return result;
	}

	// 되돌리지 못해도 요청의 결과를 바꾸지 않는다. 일은 이미 끝났다(커밋됐거나 실패했다).
	// 되돌리지 못한 한 번은 거부로 남아 이 계정의 한도를 하나 줄인다.
	private void refund(long accountId) {
		try {
			limiter.refund(limit, accountId);
		}
		catch (RuntimeException ex) {
			log.error("거부가 아닌 요청의 횟수를 되돌리지 못했다. 이 계정의 한도가 하나 줄어든다: accountId={}", accountId, ex);
		}
	}

}
