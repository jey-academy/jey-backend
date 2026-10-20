package com.jey.dongtanpass.application;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

import com.jey.core.audit.api.AuditEntry;
import com.jey.core.audit.api.AuditLog;
import com.jey.core.auth.api.CurrentActor;
import com.jey.core.member.api.ContactInfo;
import com.jey.core.member.api.MemberDirectory;
import com.jey.core.member.api.StudentInfo;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.EventMetadata;
import com.jey.core.shared.PhoneProtector;
import com.jey.dongtanpass.api.CampusPassIssued;
import com.jey.dongtanpass.api.PassRefs;
import com.jey.dongtanpass.domain.Consent;
import com.jey.dongtanpass.domain.ConsentRepository;
import com.jey.dongtanpass.domain.LinkToken;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassDirection;
import com.jey.dongtanpass.domain.PassRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JEY 지점이 다니는 학생에게 패스를 발급하고, 학부모 링크를 새로 만들고, 쓰지 않은 패스를 취소한다(연계 학원에서 쓴다).
 * 관리자만 부른다(컨트롤러가 막는다). 연계 학원이 발급한 패스는 여기서 없는 것과 같다(404).
 *
 * <p>대상은 패스 제도에 참여하는 지점({@link PassBenefits#current()}의 지점)에 재원 중인 학생이고, 월 결제마다 받을 수 있다
 * (같은 학생이 여러 장). 신규 조건은 할인을 주는 연계 학원 쪽의 것이라 여기서 보지 않는다.
 * 학생 이름과 학부모 번호는 학생 자료에서 가져온다: 번호는 대표 연락처(어머니 → 먼저 연결된 학부모 → 학생 본인)이고 전체 번호 색인만 저장한다.
 * 할인율은 발급 때의 설정({@link CampusBenefitService})을 패스에 남긴다. 발급은 이벤트({@code dongtanpass.campus-pass-issued})로 감사 로그에 남는다.
 */
@Service
public class CampusPassService {

	private final PassRepository passes;

	private final ConsentRepository consents;

	private final TransactionTemplate transaction;

	private final MemberDirectory members;

	private final PassBenefits passBenefits;

	private final CampusBenefitService campusBenefits;

	private final PhoneProtector phoneProtector;

	private final ApplicationEventPublisher events;

	private final CurrentActor currentActor;

	private final Clock clock;

	private final PassNumbers passNumbers;

	private final AuditLog auditLog;

	private final SecureRandom random = new SecureRandom();

	CampusPassService(PassRepository passes, ConsentRepository consents, TransactionTemplate transaction,
			MemberDirectory members, PassBenefits passBenefits, CampusBenefitService campusBenefits,
			PhoneProtector phoneProtector, ApplicationEventPublisher events, CurrentActor currentActor, Clock clock,
			PassNumbers passNumbers, AuditLog auditLog) {
		this.passes = passes;
		this.consents = consents;
		this.transaction = transaction;
		this.members = members;
		this.passBenefits = passBenefits;
		this.campusBenefits = campusBenefits;
		this.phoneProtector = phoneProtector;
		this.events = events;
		this.currentActor = currentActor;
		this.clock = clock;
		this.passNumbers = passNumbers;
		this.auditLog = auditLog;
	}

	/**
	 * 패스를 발급한다. 동의 기록과 함께 한 트랜잭션에서 저장하고 이벤트를 낸다. 패스 번호가 겹치면 다시 한다({@link PassIssuing}).
	 *
	 * @throws BusinessException 동의를 확인하지 않았거나 고지 버전이 JEY 발급의 지금 버전이 아닐 때, 결제일이 오늘보다 뒤이거나
	 * 금액이 0원·1원 단위가 아닐 때(400), 없는 학생(404), 퇴원한 학생, 발급 지점의 학생이 아님, 연락처가 하나도 없음(409)
	 */
	public CampusIssuedPass issue(NewCampusPass input) {
		if (!input.consentConfirmed()) {
			throw new BusinessException(DongtanpassErrorCode.CONSENT_NOT_CONFIRMED);
		}
		if (!ConsentNotice.isCurrent(PassDirection.CAMPUS_ISSUED, input.noticeVersion())) {
			throw new BusinessException(DongtanpassErrorCode.NOTICE_VERSION_MISMATCH);
		}
		PassIssuing.requirePayment(input.paidOn(), input.paidAmount(), clock);
		long campusId = passBenefits.current().campusId();
		StudentInfo student = members.findStudent(input.studentId())
			.orElseThrow(() -> new BusinessException(DongtanpassErrorCode.STUDENT_NOT_FOUND));
		if (!student.enrolled()) {
			throw new BusinessException(DongtanpassErrorCode.STUDENT_NOT_ENROLLED);
		}
		if (student.campusId() != campusId) {
			throw new BusinessException(DongtanpassErrorCode.STUDENT_NOT_AT_ISSUING_CAMPUS);
		}
		ContactInfo contact = members.primaryContact(student.studentId())
			.orElseThrow(() -> new BusinessException(DongtanpassErrorCode.STUDENT_HAS_NO_CONTACT));
		String phoneIndex = phoneProtector.fullIndex(contact.phone());
		return PassIssuing.withPassNoRetry(
				() -> transaction.execute(status -> save(campusId, student, phoneIndex, input)), "campusId=" + campusId);
	}

	/**
	 * 학부모 링크를 새로 만든다. 예전 링크는 바로 쓸 수 없다. 기간이 지난 패스도 된다.
	 *
	 * @throws BusinessException 이 지점이 발급한 패스가 아닐 때(404 — 연계 학원 발급분도), 사용됐거나 취소된 패스일 때(409)
	 */
	public RenewedLink renewLink(long passId) {
		long campusId = passBenefits.current().campusId();
		return transaction.execute(status -> {
			Pass pass = lockedPass(campusId, passId);
			LinkToken link = LinkToken.generate(random);
			PassService.changeState(() -> pass.renewLink(link.hash()));
			auditLog.record(AuditEntry.of(DongtanpassAuditAction.PASS_LINK_RENEWED).target(PassRefs.pass(passId)));
			return new RenewedLink(passId, link.raw());
		});
	}

	/**
	 * 쓰지 않은 패스를 취소한다. 기간이 지난 패스도 된다. 결제 환불은 따로 한다.
	 *
	 * @throws BusinessException 이 지점이 발급한 패스가 아닐 때(404 — 연계 학원 발급분도), 사용됐거나 이미 취소된 패스일 때(409)
	 */
	public CanceledPass cancel(long passId) {
		long campusId = passBenefits.current().campusId();
		return transaction.execute(status -> {
			Pass pass = lockedPass(campusId, passId);
			PassService.changeState(pass::cancel);
			auditLog.record(AuditEntry.of(DongtanpassAuditAction.PASS_CANCELED).target(PassRefs.pass(passId)));
			return new CanceledPass(passId, pass.stateOn(LocalDate.now(clock)));
		});
	}

	private Pass lockedPass(long campusId, long passId) {
		return passes.findByIdAndIssuerCampusIdForUpdate(passId, campusId)
			.orElseThrow(() -> new BusinessException(DongtanpassErrorCode.PASS_NOT_FOUND));
	}

	private CampusIssuedPass save(long campusId, StudentInfo student, String phoneIndex, NewCampusPass input) {
		Instant now = clock.instant();
		LinkToken link = LinkToken.generate(random);
		// 같은 트랜잭션에서 읽는다. 발급과 할인율 변경이 겹쳐도 패스에는 그 순간의 값 하나가 남는다.
		BigDecimal rate = campusBenefits.currentRate();
		// 번호가 겹치면 여기서 유니크 제약에 걸린다(커밋 때까지 미루지 않는다).
		Pass pass = passes.saveAndFlush(Pass.issueAtCampus(campusId, student.studentId(), passNumbers.next(),
				link.hash(), student.name(), phoneIndex, now, clock.getZone(), input.paidOn(), input.paidAmount(),
				input.paymentMethod(), rate));
		consents.save(Consent.thirdPartyProvision(pass.getId(), PassDirection.CAMPUS_ISSUED, input.noticeVersion(), now));
		// 같은 트랜잭션에서 낸다. 발급이 커밋돼야 이벤트도 나간다.
		events.publishEvent(new CampusPassIssued(
				EventMetadata.create(CampusPassIssued.TYPE, CampusPassIssued.VERSION, campusId, currentActor.get(),
						clock),
				pass.getId(), campusId, student.studentId()));
		return new CampusIssuedPass(pass.getId(), pass.getPassNo(), link.raw(), now, pass.getExpiresOn(),
				pass.getDiscountRate());
	}

}
