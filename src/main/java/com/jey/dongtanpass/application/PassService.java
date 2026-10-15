package com.jey.dongtanpass.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

import com.jey.core.auth.api.CurrentActor;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.EventMetadata;
import com.jey.core.shared.PhoneProtector;
import com.jey.dongtanpass.api.PassIssued;
import com.jey.dongtanpass.domain.Consent;
import com.jey.dongtanpass.domain.ConsentRepository;
import com.jey.dongtanpass.domain.LinkToken;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassNumber;
import com.jey.dongtanpass.domain.PassRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 연계 학원이 패스를 발급한다(ADR-0001, ADR-0035).
 *
 * <p>학부모 번호는 전체 번호 색인만 저장한다. 링크 토큰은 해시만 저장하고 원문은 돌려주는 값에만 싣는다.
 * 발급은 이벤트({@code dongtanpass.pass-issued})로 감사 로그에 남는다.
 */
@Service
public class PassService {

	private final PassRepository passes;

	private final ConsentRepository consents;

	private final TransactionTemplate transaction;

	private final PhoneProtector phoneProtector;

	private final ApplicationEventPublisher events;

	private final CurrentActor currentActor;

	private final Clock clock;

	private final SecureRandom random = new SecureRandom();

	PassService(PassRepository passes, ConsentRepository consents, TransactionTemplate transaction,
			PhoneProtector phoneProtector, ApplicationEventPublisher events, CurrentActor currentActor, Clock clock) {
		this.passes = passes;
		this.consents = consents;
		this.transaction = transaction;
		this.phoneProtector = phoneProtector;
		this.events = events;
		this.currentActor = currentActor;
		this.clock = clock;
	}

	/**
	 * 패스를 발급한다. 동의 기록과 함께 한 트랜잭션에서 저장하고 이벤트를 낸다.
	 *
	 * @throws BusinessException 동의를 확인하지 않았거나 고지 버전이 다를 때, 결제일이 오늘보다 뒤일 때,
	 * 결제 금액이 0원이거나 1원 단위가 아닐 때
	 */
	public IssuedPass issue(long partnerId, NewPass input) {
		validate(input);
		String phoneIndex = phoneProtector.fullIndex(input.guardianPhone());
		return transaction.execute(status -> save(partnerId, input, phoneIndex));
	}

	private IssuedPass save(long partnerId, NewPass input, String phoneIndex) {
		Instant now = clock.instant();
		LinkToken link = LinkToken.generate(random);
		// 번호가 겹치면 여기서 유니크 제약에 걸린다(커밋 때까지 미루지 않는다).
		Pass pass = passes.saveAndFlush(Pass.issue(partnerId, PassNumber.generate(random), link.hash(),
				input.studentName(), phoneIndex, now, LocalDate.now(clock), input.paidOn(), input.paidAmount(),
				input.paymentMethod()));
		consents.save(Consent.thirdPartyProvision(pass.getId(), input.noticeVersion(), now));
		// 같은 트랜잭션에서 낸다. 발급이 커밋돼야 이벤트도 나간다.
		events.publishEvent(new PassIssued(
				EventMetadata.create(PassIssued.TYPE, PassIssued.VERSION, null, currentActor.get(), clock),
				pass.getId(), partnerId));
		return new IssuedPass(pass.getId(), pass.getPassNo(), link.raw(), now, pass.getExpiresOn());
	}

	private void validate(NewPass input) {
		if (!input.consentConfirmed()) {
			throw new BusinessException(DongtanpassErrorCode.CONSENT_NOT_CONFIRMED);
		}
		if (!ConsentNotice.isCurrent(input.noticeVersion())) {
			throw new BusinessException(DongtanpassErrorCode.NOTICE_VERSION_MISMATCH);
		}
		if (input.paidOn().isAfter(LocalDate.now(clock))) {
			throw new BusinessException(DongtanpassErrorCode.PAID_ON_IN_FUTURE);
		}
		// 반 수강료와 같은 규칙이다.
		if (input.paidAmount().isZero() || !input.paidAmount().isWholeWon()) {
			throw new BusinessException(DongtanpassErrorCode.INVALID_PAID_AMOUNT);
		}
	}

}
