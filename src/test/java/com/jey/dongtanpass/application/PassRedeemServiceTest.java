package com.jey.dongtanpass.application;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import com.jey.core.audit.api.AuditLog;
import com.jey.core.auth.api.AccountRateLimiter;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.CurrentActor;
import com.jey.core.auth.api.UserRole;
import com.jey.core.member.api.MemberDirectory;
import com.jey.core.member.api.StudentInfo;
import com.jey.core.shared.Actor;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.Money;
import com.jey.dongtanpass.api.PassRedeemed;
import com.jey.dongtanpass.domain.LinkToken;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassNumber;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.PaymentMethod;
import com.jey.dongtanpass.domain.Redemption;
import com.jey.dongtanpass.domain.RedemptionRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 경합 때만 지나는 예외 변환을 정해진 순서로 확인한다(동시성 테스트는 그 순서가 올지 운에 달려 있다). Docker가 필요 없다.
// 저장소는 목이고, 트랜잭션은 콜백을 그대로 부른다.
class PassRedeemServiceTest {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T01:00:00Z"), SEOUL);

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final long PASS_ID = 11L;

	private static final long STUDENT_ID = 21L;

	private static final long PARTNER_ID = 7L;

	private static final AuthenticatedUser STAFF = new AuthenticatedUser(31L, "desk", "데스크", UserRole.STAFF, 1L, null);

	private final PassRepository passes = mock(PassRepository.class);

	private final RedemptionRepository redemptions = mock(RedemptionRepository.class);

	private final MemberDirectory members = mock(MemberDirectory.class);

	private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

	private final PassBenefits passBenefits = mock(PassBenefits.class);

	private final TransactionTemplate transaction = mock(TransactionTemplate.class);

	private final CurrentActor currentActor = mock(CurrentActor.class);

	private PassRedeemService service;

	@BeforeEach
	void setUp() {
		when(passBenefits.current())
			.thenReturn(new PassBenefit(1L, "동탄관", "DONGTAN_PASS", new BigDecimal("0.05"), true));
		when(transaction.execute(any())).thenAnswer(call -> call.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
		when(currentActor.get()).thenReturn(new Actor.User(STAFF.id()));
		when(passes.findById(PASS_ID)).thenReturn(Optional.of(issuedPass()));
		when(members.findStudent(STUDENT_ID)).thenReturn(Optional.of(
				new StudentInfo(STUDENT_ID, 1L, null, "김하윤", true, CLOCK.instant().minus(Duration.ofHours(1)),
						"동탄고1")));
		when(members.hasGuardianWithPhoneIndex(STUDENT_ID, "phone-index")).thenReturn(true);
		service = serviceWith(new OptimisticRedeemLocking(passes, redemptions));
	}

	private PassRedeemService serviceWith(RedeemLocking locking) {
		return new PassRedeemService(passes, redemptions, locking, passBenefits, members, transaction, events,
				currentActor, new RedeemProperties(Duration.ofDays(7)), mock(AccountRateLimiter.class),
				new RedeemLimitProperties(30, Duration.ofHours(1)), mock(AuditLog.class), CLOCK);
	}

	// 서비스가 저장소를 직접 부르면 비교 실험의 방식(행 잠금, 기록 먼저, 분산 락)이 조용히 운영 방식과 같아진다.
	// 패스를 읽고 저장하는 일은 RedeemLocking에만 맡기고, 트랜잭션은 guard 안에서 돌아야 한다(분산 락이 커밋까지 감싼다).
	@Test
	void 패스를_읽고_저장하는_일은_RedeemLocking에_맡기고_트랜잭션은_guard_안에서_돈다() {
		RecordingLocking locking = new RecordingLocking(issuedPass());
		doAnswer(call -> {
			assertThat(locking.inGuard).as("트랜잭션이 guard 안에서 돈다").isTrue();
			return call.<TransactionCallback<?>>getArgument(0).doInTransaction(null);
		}).when(transaction).execute(any());

		RedeemedPass result = serviceWith(locking).redeem(PASS_ID, STUDENT_ID, STAFF);

		assertThat(locking.guardedPassId).isEqualTo(PASS_ID);
		assertThat(locking.loadedPassId).isEqualTo(PASS_ID);
		assertThat(locking.stored).isNotNull();
		assertThat(locking.stored.getStudentId()).isEqualTo(STUDENT_ID);
		assertThat(result.redemptionId()).isEqualTo(41L);
		assertThat(locking.inGuard).isFalse();
		verify(passes, never()).findById(any());
		verify(passes, never()).saveAndFlush(any());
		verify(redemptions, never()).saveAndFlush(any());
	}

	// 분산 락에 걸러진 요청은 다시 시도(409) 그대로 나간다(트랜잭션을 열지 않는다).
	@Test
	void guard가_거른_요청은_그_거부가_그대로_나간다() {
		RedeemLocking rejecting = new RecordingLocking(issuedPass()) {
			@Override
			public <T> T guard(long passId, java.util.function.Supplier<T> work) {
				throw new BusinessException(DongtanpassErrorCode.PASS_CHANGED_CONCURRENTLY);
			}
		};

		assertThatThrownBy(() -> serviceWith(rejecting).redeem(PASS_ID, STUDENT_ID, STAFF)).isInstanceOfSatisfying(
				BusinessException.class,
				ex -> assertThat(ex.getErrorCode()).isEqualTo(DongtanpassErrorCode.PASS_CHANGED_CONCURRENTLY));
		verify(transaction, never()).execute(any());
	}

	// 서비스가 무엇을 맡겼는지 기록한다. 저장한 기록에는 ID를 붙여 돌려준다.
	private static class RecordingLocking implements RedeemLocking {

		private final Pass pass;

		boolean inGuard;

		Long guardedPassId;

		Long loadedPassId;

		Redemption stored;

		RecordingLocking(Pass pass) {
			this.pass = pass;
		}

		@Override
		public <T> T guard(long passId, java.util.function.Supplier<T> work) {
			guardedPassId = passId;
			inGuard = true;
			try {
				return work.get();
			}
			finally {
				inGuard = false;
			}
		}

		@Override
		public Optional<Pass> load(long passId) {
			loadedPassId = passId;
			return Optional.of(pass);
		}

		@Override
		public Redemption store(Pass pass, Redemption redemption) {
			stored = redemption;
			ReflectionTestUtils.setField(redemption, "id", 41L);
			return redemption;
		}

	}

	// 성공하면 이벤트에 패스·학생·연계 학원 ID가 실린다(수납과 이을 때 읽는 값이다). 봉투의 지점은 받는 지점이다.
	@Test
	void 사용_처리_이벤트에_패스_학생_연계_학원이_실린다() {
		when(redemptions.saveAndFlush(any())).thenAnswer(call -> {
			Object saved = call.getArgument(0);
			ReflectionTestUtils.setField(saved, "id", 41L);
			return saved;
		});

		service.redeem(PASS_ID, STUDENT_ID, STAFF);

		ArgumentCaptor<PassRedeemed> event = ArgumentCaptor.forClass(PassRedeemed.class);
		verify(events).publishEvent(event.capture());
		assertThat(event.getValue().passId()).isEqualTo(PASS_ID);
		assertThat(event.getValue().studentId()).isEqualTo(STUDENT_ID);
		assertThat(event.getValue().partnerId()).isEqualTo(PARTNER_ID);
		assertThat(event.getValue().metadata().campusId()).isEqualTo(1L);
		assertThat(event.getValue().metadata().actorId()).isEqualTo(STAFF.id());
	}

	// 패스를 먼저 고치고 기록을 나중에 넣는다. 거꾸로면 외래 키의 공유 잠금 때문에 같은 패스를 동시에 쓰는 요청끼리 데드락이 난다.
	@Test
	void 운영_방식은_패스를_먼저_저장하고_기록을_나중에_저장한다() {
		when(redemptions.saveAndFlush(any())).thenAnswer(call -> {
			Object saved = call.getArgument(0);
			ReflectionTestUtils.setField(saved, "id", 41L);
			return saved;
		});

		service.redeem(PASS_ID, STUDENT_ID, STAFF);

		InOrder order = inOrder(passes, redemptions);
		order.verify(passes).saveAndFlush(any());
		order.verify(redemptions).saveAndFlush(any());
	}

	// 링크 새로 만들기에 졌다. 패스는 아직 쓸 수 있으므로 다시 시도하라고 답한다(멱등 응답으로 보관하지 않는 코드).
	@Test
	void 버전_충돌에_졌는데_패스가_아직_쓸_수_있으면_다시_시도다() {
		when(passes.saveAndFlush(any())).thenThrow(conflict());
		when(passes.findById(PASS_ID)).thenReturn(Optional.of(issuedPass()), Optional.of(issuedPass()));

		assertThatThrownBy(() -> service.redeem(PASS_ID, STUDENT_ID, STAFF))
			.isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getErrorCode())
				.isEqualTo(DongtanpassErrorCode.PASS_CHANGED_CONCURRENTLY));
	}

	// 다시 읽은 지금 상태로 답한다(취소에 졌으면 취소됨, 다른 사용 처리에 졌으면 이미 사용됨).
	@Test
	void 버전_충돌에_졌으면_다시_읽은_상태로_답한다() {
		Pass canceled = issuedPass();
		canceled.cancel();
		Pass redeemed = issuedPass();
		redeemed.redeem(99L, 1L, CLOCK.instant());
		when(passes.saveAndFlush(any())).thenThrow(conflict());

		when(passes.findById(PASS_ID)).thenReturn(Optional.of(issuedPass()), Optional.of(canceled));
		assertThatThrownBy(() -> service.redeem(PASS_ID, STUDENT_ID, STAFF)).isInstanceOfSatisfying(
				BusinessException.class,
				ex -> assertThat(ex.getErrorCode()).isEqualTo(DongtanpassErrorCode.PASS_CANCELED));

		when(passes.findById(PASS_ID)).thenReturn(Optional.of(issuedPass()), Optional.of(redeemed));
		assertThatThrownBy(() -> service.redeem(PASS_ID, STUDENT_ID, STAFF)).isInstanceOfSatisfying(
				BusinessException.class,
				ex -> assertThat(ex.getErrorCode()).isEqualTo(DongtanpassErrorCode.PASS_ALREADY_REDEEMED));
	}

	// 다시 읽다 DB 오류가 나면 그 오류로 끝내되, 원래의 충돌도 함께 남는다(로그에서 원인을 찾을 수 있게).
	@Test
	void 버전_충돌_뒤_다시_읽지_못하면_원래_충돌을_덧붙여_던진다() {
		ObjectOptimisticLockingFailureException conflict = conflict();
		when(passes.saveAndFlush(any())).thenThrow(conflict);
		IllegalStateException readFailure = new IllegalStateException("DB에 닿지 못했다");
		when(passes.findById(PASS_ID)).thenReturn(Optional.of(issuedPass())).thenThrow(readFailure);

		assertThatThrownBy(() -> service.redeem(PASS_ID, STUDENT_ID, STAFF)).isSameAs(readFailure)
			.satisfies(ex -> assertThat(ex.getSuppressed()).containsExactly(conflict));
	}

	// 같은 학생을 먼저 쓴 요청이 커밋되기 전에 확인을 지나 넣다가 유니크 키에 걸렸다.
	@Test
	void 학생_유니크_키에_걸리면_신규가_아니다() {
		when(redemptions.saveAndFlush(any())).thenThrow(violationOf("uk_dongtanpass_redemption_student"));

		assertThatThrownBy(() -> service.redeem(PASS_ID, STUDENT_ID, STAFF)).isInstanceOfSatisfying(
				BusinessException.class,
				ex -> assertThat(ex.getErrorCode()).isEqualTo(DongtanpassErrorCode.STUDENT_NOT_NEW));
	}

	@Test
	void 패스_유니크_키에_걸리면_이미_사용됨이다() {
		when(redemptions.saveAndFlush(any())).thenThrow(violationOf("uk_dongtanpass_redemption_pass"));

		assertThatThrownBy(() -> service.redeem(PASS_ID, STUDENT_ID, STAFF)).isInstanceOfSatisfying(
				BusinessException.class,
				ex -> assertThat(ex.getErrorCode()).isEqualTo(DongtanpassErrorCode.PASS_ALREADY_REDEEMED));
	}

	// 다른 제약(외래 키 등)은 업무 오류로 바꾸지 않는다(서버 오류로 드러나야 한다).
	@Test
	void 다른_제약에_걸리면_그대로_던진다() {
		DataIntegrityViolationException other = violationOf("fk_dongtanpass_redemption_pass");
		when(redemptions.saveAndFlush(any())).thenThrow(other);

		assertThatThrownBy(() -> service.redeem(PASS_ID, STUDENT_ID, STAFF)).isSameAs(other);
	}

	private static Pass issuedPass() {
		Pass pass = Pass.issue(PARTNER_ID, PassNumber.generate(RANDOM), LinkToken.generate(RANDOM).hash(), "김하윤",
				"phone-index", CLOCK.instant(), SEOUL, LocalDate.now(CLOCK), Money.of(150000), PaymentMethod.CARD);
		ReflectionTestUtils.setField(pass, "id", PASS_ID);
		return pass;
	}

	private static ObjectOptimisticLockingFailureException conflict() {
		return new ObjectOptimisticLockingFailureException(Pass.class, PASS_ID);
	}

	// MySQL은 키 이름을 "테이블.키"로 준다.
	private static DataIntegrityViolationException violationOf(String key) {
		return new DataIntegrityViolationException("duplicate",
				new ConstraintViolationException("duplicate", new SQLException("duplicate"),
						"dongtanpass_redemption." + key));
	}

}
