package com.jey.core.member;

import com.jey.core.audit.api.AuditEntry;
import com.jey.core.audit.api.AuditLog;
import com.jey.core.audit.api.DeniedAttempts;
import com.jey.core.auth.api.AccountRateLimiter;
import com.jey.core.auth.api.AccountRateLimiter.Blocked;
import com.jey.core.auth.api.RateLimit;
import com.jey.core.shared.RateLimitedException;

/**
 * 횟수 한도에 막힌 요청을 거부로 바꾸고, 막힌 사실을 감사 로그에 남긴다.
 */
final class LimitBlocks {

	private LimitBlocks() {
	}

	/**
	 * 막힌 계정이 계속 보내면 줄이 끝없이 늘므로 구간마다 처음 막힌 때만 적는다.
	 *
	 * <p>"처음 막혔다"는 표시는 횟수 저장소에 먼저 남는다. 그 뒤에 기록이 실패하면 표시만 남아 이 구간에서는 다시 적히지 않는다.
	 * 그래서 기록하지 못했으면 표시를 지워, 다음에 막힌 요청이 다시 적게 한다. 이번 요청에는 기록의 실패가 나간다.
	 *
	 * @return 던질 거부. 호출하는 쪽이 던진다
	 * @throws RuntimeException 막힌 사실을 기록하지 못했을 때
	 */
	static RateLimitedException rejection(AccountRateLimiter limiter, AuditLog auditLog, RateLimit limit,
			long accountId, Blocked blocked, MemberAuditAction limitedAction, MemberErrorCode limitedCode) {
		RateLimitedException denial = new RateLimitedException(limitedCode, blocked.retryAfter());
		if (blocked.firstInWindow()) {
			try {
				DeniedAttempts.record(auditLog, () -> AuditEntry.of(limitedAction), denial);
			}
			catch (RuntimeException failure) {
				try {
					limiter.forgetBlockNotice(limit, accountId);
				}
				catch (RuntimeException forgetFailure) {
					failure.addSuppressed(forgetFailure);
				}
				throw failure;
			}
		}
		return denial;
	}

}
