package com.jey.core.member;

import java.util.function.Supplier;

import com.jey.core.audit.api.AuditEntry;
import com.jey.core.audit.api.AuditLog;
import com.jey.core.audit.api.DeniedAttempts;
import com.jey.core.auth.api.AccountRateLimiter;
import com.jey.core.auth.api.AccountRateLimiter.Permit;
import com.jey.core.auth.api.CurrentActor;
import com.jey.core.auth.api.RateLimit;
import com.jey.core.shared.Actor;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.RateLimitedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 전화번호가 실린 변경 요청을 감싸서, 번호를 바꿔 가며 넣어 가린 번호를 알아내는 일을 늦춘다(ADR-0026).
 *
 * <p>"그 번호가 이미 있다"는 거부는 맞는 번호를 넣었을 때만 나온다. 가린 번호의 가운데 자리를 바꿔 가며 넣으면 번호를 알아낼 수 있다(ADR-0023).
 * 한도를 둘 건다.
 * <ul>
 * <li>번호가 실린 변경 요청 자체. 성공해도 센다. 틀린 번호를 넣은 요청은 거부가 아니라 성공으로 끝나므로(번호가 바뀌거나
 * 새 학부모가 생긴다), 이것이 없으면 틀린 대입은 한도 없이 할 수 있다.</li>
 * <li>"그 번호가 이미 있다"는 거부. 먼저 세고, 그런 거부로 끝나지 않았으면 되돌린다. 맞힌 번호를 잇달아 확인하는 것을 막는다.</li>
 * </ul>
 * 어느 쪽이든 한도에 닿으면 풀릴 때까지 이 길을 지나는 요청을 모두 막는다. 맞는 번호를 넣은 요청도 막힌다.
 *
 * <p>일을 하기 전에 센다. 답을 받은 뒤에 세면 동시에 보낸 요청이 모두 확인을 통과한다.
 * 트랜잭션 밖에서 부른다. 막힌 사실은 트랜잭션 밖에서 적어야 한다.
 */
@Component
class PhoneProbeGuard {

	private static final Logger log = LoggerFactory.getLogger(PhoneProbeGuard.class);

	private final AccountRateLimiter limiter;

	private final RateLimit changeLimit;

	private final RateLimit probeLimit;

	private final CurrentActor currentActor;

	private final AuditLog auditLog;

	PhoneProbeGuard(AccountRateLimiter limiter, MemberLimitProperties limits, CurrentActor currentActor,
			AuditLog auditLog) {
		this.limiter = limiter;
		this.changeLimit = limits.phoneChange();
		this.probeLimit = limits.phoneProbe();
		this.currentActor = currentActor;
		this.auditLog = auditLog;
		// 값을 잘못 넣어(지나치게 큰 한도) 제한이 꺼진 것을 뜰 때 알아볼 수 있게 한다.
		log.info("전화번호 한도: 보기 {}, 번호가 실린 변경 {}, 번호가 드러나는 거부 {}", limits.phoneReveal(), changeLimit,
				probeLimit);
	}

	/**
	 * @param work 전화번호가 실린 변경. 번호가 드러나는 거부는 {@link BusinessException}으로 던진다
	 * @throws RateLimitedException 이 계정이 한도에 닿아 막혀 있을 때. {@code work}는 실행되지 않는다
	 */
	<T> T guard(Supplier<T> work) {
		// 로그인 없이 도는 일(초기 자료 넣기 등)은 셀 계정이 없다. 응답을 받아 볼 사람도 없다.
		if (!(currentActor.get() instanceof Actor.User requester)) {
			log.debug("로그인한 계정이 없어 전화번호 한도를 세지 않는다");
			return work.get();
		}
		limiter.acquire(changeLimit, requester, MemberErrorCode.PHONE_CHANGE_LIMITED, denial -> DeniedAttempts
			.record(auditLog, () -> AuditEntry.of(MemberAuditAction.PHONE_CHANGE_LIMITED), denial));
		Permit probe = limiter.acquire(probeLimit, requester, MemberErrorCode.PHONE_CHANGE_LIMITED,
				denial -> DeniedAttempts.record(auditLog, () -> AuditEntry.of(MemberAuditAction.PHONE_PROBE_LIMITED),
						denial));
		T result;
		try {
			result = work.get();
		}
		catch (BusinessException ex) {
			if (!(ex.getErrorCode() instanceof MemberErrorCode code && code.revealsExistingPhone())) {
				refund(probe);
			}
			throw ex;
		}
		// 그 밖의 예외는 되돌리지 않는다. 번호가 드러나는 거부를 기록하다 실패하면 그 거부가 서버 오류로 바뀌어 나가는데,
		// 그 오류는 맞는 번호에서만 나오므로 여전히 번호가 드러난다. 무엇이 일어났는지 모르는 실패는 드러난 것으로 센다.
		refund(probe);
		return result;
	}

	// 되돌리지 못해도 요청의 결과를 바꾸지 않는다. 일은 이미 끝났다(커밋됐거나 거부됐다).
	// 되돌리지 못한 한 번은 거부로 남아 이 계정의 한도를 하나 줄인다.
	private void refund(Permit permit) {
		try {
			limiter.refund(permit);
		}
		catch (RuntimeException ex) {
			log.error("거부가 아닌 요청의 횟수를 되돌리지 못했다. 이 계정의 한도가 하나 줄어든다: limit={} accountId={}",
					permit.limit().name(), permit.account().accountId(), ex);
		}
	}

}
