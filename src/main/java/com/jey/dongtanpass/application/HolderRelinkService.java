package com.jey.dongtanpass.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.jey.core.audit.api.AuditEntry;
import com.jey.core.audit.api.AuditLog;
import com.jey.core.audit.api.DeniedAttempts;
import com.jey.core.auth.api.ClientAttemptLimiter;
import com.jey.core.auth.api.ClientAttemptLimiter.Attempt;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.PhoneNumber;
import com.jey.core.shared.PhoneProtector;
import com.jey.dongtanpass.api.PassRefs;
import com.jey.dongtanpass.domain.LinkToken;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.PassState;
import com.jey.dongtanpass.domain.StudentNameKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 링크를 잃어버린 학부모가 학생 이름과 전체 번호로 링크를 다시 받는다(ADR-0035 개정). 학부모는 로그인하지 않는다.
 *
 * <p>번호 색인으로 패스를 읽고 이름 대조 값으로 거른다. 맞는 패스 중 쓸 수 있는 것(발급 상태, 기간 안)이 있으면 가장 최근에 발급된
 * 하나의 링크를 새로 만들고 옛 링크를 막는다. 쓸 수 있는 것이 없으면 가장 최근 패스의 사정으로 409, 맞는 패스가 없으면 404 하나다
 * (이름과 번호 중 어느 쪽이 틀렸는지 알리지 않는다).
 *
 * <p>틀린 시도는 확인하기 전에 "번호 + 접속 주소"와 "접속 주소"로 세고, 이름과 번호가 맞았으면(성공, 409) 되돌린다.
 *
 * <p>거부를 감사 로그에 남기므로 메서드에 {@code @Transactional}을 붙이지 않는다. 트랜잭션은 안에서 열고, 거부는 그 밖에서 적는다(ADR-0024).
 */
@Service
@EnableConfigurationProperties(HolderRelinkProperties.class)
public class HolderRelinkService {

	private static final Logger log = LoggerFactory.getLogger(HolderRelinkService.class);

	// 발급 시각이 늦은 것부터. 같으면 나중에 저장된 것(ID가 큰 것)부터.
	private static final Comparator<Pass> LATEST_FIRST = Comparator.comparing(Pass::getIssuedAt)
		.thenComparing(Pass::getId)
		.reversed();

	private final PassRepository passes;

	private final TransactionTemplate transaction;

	private final PhoneProtector phoneProtector;

	private final ClientAttemptLimiter limiter;

	private final HolderRelinkProperties limits;

	private final AuditLog auditLog;

	private final Clock clock;

	private final SecureRandom random = new SecureRandom();

	HolderRelinkService(PassRepository passes, TransactionTemplate transaction, PhoneProtector phoneProtector,
			ClientAttemptLimiter limiter, HolderRelinkProperties limits, AuditLog auditLog, Clock clock) {
		this.passes = passes;
		this.transaction = transaction;
		this.phoneProtector = phoneProtector;
		this.limiter = limiter;
		this.limits = limits;
		this.auditLog = auditLog;
		this.clock = clock;
	}

	/**
	 * @param clientAddress 요청의 접속 주소. 틀린 시도를 센다
	 * @throws BusinessException 맞는 패스가 없을 때(404), 맞았지만 쓸 수 있는 패스가 없을 때(409),
	 * 틀린 시도 한도에 막혔을 때(429)
	 */
	public RelinkedLink relink(String studentName, PhoneNumber guardianPhone, String clientAddress) {
		String phoneIndex = phoneProtector.fullIndex(guardianPhone);
		Attempt attempt = limiter.acquire(limits.perPhoneAndClient(), limits.perClient(), phoneIndex, clientAddress,
				DongtanpassErrorCode.HOLDER_RELINK_LIMITED, denial -> DeniedAttempts.record(auditLog,
						() -> AuditEntry.of(DongtanpassAuditAction.HOLDER_RELINK_LIMITED), denial));
		try {
			Optional<StudentNameKey> name = nameKeyOf(studentName);
			RelinkedLink relinked;
			try {
				relinked = chooseAndRenew(name, phoneIndex);
			}
			catch (UnusablePassException changed) {
				// 고른 패스가 잠그기 전에 쓰이거나 취소됐다. 쓸 수 있는 다른 패스가 있을 수 있어 한 번만 다시 고른다.
				relinked = chooseAndRenew(name, phoneIndex);
			}
			refund(attempt, "renewed");
			return relinked;
		}
		catch (UnusablePassException denial) {
			refund(attempt, denial.code.code());
			DeniedAttempts.record(auditLog, () -> AuditEntry.of(DongtanpassAuditAction.HOLDER_LINK_DENIED)
				.target(PassRefs.pass(denial.passId))
				.with("reason", denial.code), denial);
			throw denial;
		}
		catch (BusinessException denial) {
			if (denial.getErrorCode() == DongtanpassErrorCode.PASS_NOT_FOUND) {
				// 이름과 번호는 싣지 않는다.
				log.info("학부모 링크 다시 받기: 맞는 패스가 없다");
			}
			throw denial;
		}
	}

	// 후보는 따로 읽는다. 같은 트랜잭션에서 읽은 엔티티는 잠가 읽어도 상태가 새로 읽히지 않는다.
	private RelinkedLink chooseAndRenew(Optional<StudentNameKey> name, String phoneIndex) {
		long passId = transaction.execute(status -> choose(name, phoneIndex));
		return transaction.execute(status -> renew(passId));
	}

	// 공백뿐인 이름(줄바꿈하지 않는 공백 등, 요청 검증을 지나온 것)은 어떤 패스와도 맞지 않는다. 틀린 시도로 센다.
	private static Optional<StudentNameKey> nameKeyOf(String studentName) {
		try {
			return Optional.of(StudentNameKey.of(studentName));
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

	private long choose(Optional<StudentNameKey> name, String phoneIndex) {
		List<Pass> matched = passes.findByGuardianPhoneIndex(phoneIndex)
			.stream()
			.filter(pass -> name.isPresent() && name.get().matches(pass.getStudentName()))
			.sorted(LATEST_FIRST)
			.toList();
		if (matched.isEmpty()) {
			throw new BusinessException(DongtanpassErrorCode.PASS_NOT_FOUND);
		}
		LocalDate today = LocalDate.now(clock);
		return matched.stream()
			.filter(pass -> pass.stateOn(today) == PassState.ISSUED)
			.findFirst()
			.orElseThrow(() -> UnusablePassException.of(matched.getFirst(), today))
			.getId();
	}

	// 고르고 잠그는 사이에 쓰이거나 취소됐을 수 있다. 잠근 뒤에 다시 본다.
	private RelinkedLink renew(long passId) {
		Pass pass = passes.findByIdForUpdate(passId)
			.orElseThrow(() -> new IllegalStateException("고른 패스가 없다: passId=" + passId));
		LocalDate today = LocalDate.now(clock);
		if (pass.stateOn(today) != PassState.ISSUED) {
			throw UnusablePassException.of(pass, today);
		}
		LinkToken link = LinkToken.generate(random);
		pass.renewLink(link.hash());
		auditLog.record(AuditEntry.of(DongtanpassAuditAction.HOLDER_LINK_RENEWED).target(PassRefs.pass(passId)));
		return new RelinkedLink(link.raw());
	}

	// result: 맞은 요청이 어떻게 끝났는지(renewed 또는 409의 에러 코드). 이름과 번호는 남기지 않는다.
	private void refund(Attempt attempt, String result) {
		try {
			limiter.refund(attempt);
		}
		catch (RuntimeException ex) {
			// 되돌리지 못하면 틀린 시도가 하나 더 세어진 채로 남을 뿐이다(막는 쪽으로 틀린다). 일의 결과는 바꾸지 않는다.
			// 어느 접속 주소가 까닭 없이 막혔다는 문의를 이 로그와 맞춰 볼 수 있게 한도와 결과를 남긴다.
			log.error("학부모 링크 다시 받기: 맞은 요청의 횟수를 되돌리지 못했다: limits={}, {} result={}",
					limits.perPhoneAndClient().name(), limits.perClient().name(), result, ex);
		}
	}

	/** 이름과 번호는 맞았지만 쓸 수 있는 패스가 없다. 감사 로그에 대상 패스를 남기려고 ID를 함께 든다. */
	private static final class UnusablePassException extends BusinessException {

		private final long passId;

		private final DongtanpassErrorCode code;

		private UnusablePassException(long passId, DongtanpassErrorCode code) {
			super(code);
			this.passId = passId;
			this.code = code;
		}

		static UnusablePassException of(Pass pass, LocalDate today) {
			DongtanpassErrorCode code = switch (pass.stateOn(today)) {
				case EXPIRED -> DongtanpassErrorCode.PASS_EXPIRED;
				case CANCELED -> DongtanpassErrorCode.PASS_CANCELED;
				case REDEEMED -> DongtanpassErrorCode.PASS_ALREADY_REDEEMED;
				case ISSUED -> throw new IllegalStateException("쓸 수 있는 패스를 거부하려 했다: passId=" + pass.getId());
			};
			return new UnusablePassException(pass.getId(), code);
		}

	}

}
