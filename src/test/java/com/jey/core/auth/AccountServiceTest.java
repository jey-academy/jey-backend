package com.jey.core.auth;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.jey.core.auth.api.AccountActivity;
import com.jey.core.auth.api.AccountActivity.Kind;
import com.jey.core.auth.api.AccountAuditTrail;
import com.jey.core.auth.api.AccountRateLimiter;
import com.jey.core.auth.api.AccountRateLimiter.Permit;
import com.jey.core.auth.api.CurrentActor;
import com.jey.core.auth.api.RateLimit;
import com.jey.core.auth.api.UserRole;
import com.jey.core.auth.domain.User;
import com.jey.core.auth.domain.UserRepository;
import com.jey.core.auth.domain.UserStatus;
import com.jey.core.shared.Actor;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.CommonErrorCode;
import com.jey.core.shared.ErrorCode;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// 실제 DB와 세션으로 도는 것은 AccountManagementTest와 API 테스트가 본다. 여기서는 순서와 갈래를 본다:
// 고친 것을 커밋한 뒤에 세션을 끊는지, 거부된 시도를 트랜잭션이 끝난 뒤에 적는지, 세션을 언제 끊고 언제 끊지 않는지.
class AccountServiceTest {

	private static final long USER_ID = 7L;

	private static final long ACTOR_ID = 1L;

	private final UserRepository users = mock(UserRepository.class);

	private final UserSessions userSessions = mock(UserSessions.class);

	private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

	private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

	private final AccountAuditTrail trail = mock(AccountAuditTrail.class);

	private final CurrentActor currentActor = mock(CurrentActor.class);

	private final AccountRateLimiter limiter = mock(AccountRateLimiter.class);

	private final AccountService accountService = new AccountService(users, userSessions,
			new TransactionTemplate(transactionManager), passwordEncoder, trail, currentActor, limiter,
			new PasswordChangeLimitProperties(Duration.ofMinutes(15), 5));

	@BeforeEach
	void setUp() {
		when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		when(currentActor.get()).thenReturn(Actor.user(ACTOR_ID));
		when(users.findAllByRoleAndStatusForUpdate(UserRole.ADMIN, UserStatus.ACTIVE))
			.thenReturn(List.of(account(ACTOR_ID, UserRole.ADMIN, null)));
	}

	@AfterEach
	void clearTransactionState() {
		TransactionSynchronizationManager.setActualTransactionActive(false);
	}

	// 순서가 반대면, 세션을 끊은 직후 차단이 반영되기 전에 다시 로그인한 세션이 살아남는다.
	@Test
	void 차단을_DB에_반영한_뒤에_세션을_끊는다() {
		User user = existing(account(USER_ID, UserRole.STAFF, 1L));

		accountService.disable(USER_ID);

		assertThat(user.isActive()).isFalse();
		InOrder order = inOrder(transactionManager, userSessions);
		order.verify(transactionManager).commit(any());
		order.verify(userSessions).terminateAll(USER_ID);
		assertThat(recorded()).isEqualTo(Kind.DISABLED);
	}

	// 세션을 끊다 실패한 차단을 다시 보낸 경우다. 이미 차단돼 있어도 세션은 다시 끊는다. 기록은 한 번만 남는다.
	@Test
	void 이미_차단된_계정을_다시_차단하면_기록_없이_세션만_다시_끊는다() {
		User user = account(USER_ID, UserRole.STAFF, 1L);
		user.disable();
		existing(user);

		accountService.disable(USER_ID);

		verifyNoInteractions(trail);
		verify(userSessions).terminateAll(USER_ID);
	}

	@Test
	void 없는_계정이면_NOT_FOUND이고_세션은_건드리지_않는다() {
		when(users.findByIdForUpdate(USER_ID)).thenReturn(Optional.empty());

		assertDenied(() -> accountService.disable(USER_ID), CommonErrorCode.NOT_FOUND);
		verify(transactionManager).rollback(any());
		verifyNoInteractions(userSessions);
	}

	// 바깥 트랜잭션에 합류하면 변경이 DB에 반영되기 전에 세션을 끊게 된다. 그런 호출은 처음부터 막는다.
	@Test
	void 다른_트랜잭션_안에서는_호출할_수_없다() {
		TransactionSynchronizationManager.setActualTransactionActive(true);

		assertThatIllegalStateException().isThrownBy(() -> accountService.disable(USER_ID));
		assertThatIllegalStateException().isThrownBy(() -> accountService.enable(USER_ID));
		assertThatIllegalStateException()
			.isThrownBy(() -> accountService.update(USER_ID, new AccountChange("김직원", UserRole.STAFF, 1L)));
		assertThatIllegalStateException()
			.isThrownBy(() -> accountService.create(new NewAccount("desk", "김직원", "long-enough-password",
					UserRole.STAFF, 1L)));
		assertThatIllegalStateException()
			.isThrownBy(() -> accountService.resetPassword(USER_ID, "long-enough-password"));
		assertThatIllegalStateException()
			.isThrownBy(() -> accountService.changeOwnPassword("old-password-1", "new-password-1", session("session")));
		verifyNoInteractions(users, userSessions, transactionManager, trail, limiter);
	}

	@Test
	void 자기_자신은_차단할_수_없고_거부는_트랜잭션이_끝난_뒤에_적는다() {
		existing(account(ACTOR_ID, UserRole.ADMIN, null));

		assertDenied(() -> accountService.disable(ACTOR_ID), AuthErrorCode.SELF_CHANGE_NOT_ALLOWED);

		InOrder order = inOrder(transactionManager, trail);
		order.verify(transactionManager).rollback(any());
		order.verify(trail).denied(any(), any());
		verify(trail, never()).done(any());
		assertThat(recorded()).isEqualTo(Kind.SELF_CHANGE_DENIED);
		verifyNoInteractions(userSessions);
	}

	// 일하는 관리자가 그 사이에 차단됐을 때만 생긴다(두 관리자가 서로를 동시에 차단). 활성 관리자가 그 계정 하나뿐이다.
	@Test
	void 마지막_남은_활성_관리자는_차단할_수_없다() {
		User lastAdmin = existing(account(USER_ID, UserRole.ADMIN, null));
		when(users.findAllByRoleAndStatusForUpdate(UserRole.ADMIN, UserStatus.ACTIVE)).thenReturn(List.of(lastAdmin));

		assertDenied(() -> accountService.disable(USER_ID), AuthErrorCode.LAST_ADMIN);

		assertThat(lastAdmin.isActive()).isTrue();
		assertThat(recorded()).isEqualTo(Kind.LAST_ADMIN_DENIED);
		verifyNoInteractions(userSessions);
	}

	@Test
	void 마지막_남은_활성_관리자는_직원으로_바꿀_수_없다() {
		User lastAdmin = existing(account(USER_ID, UserRole.ADMIN, null));
		when(users.findAllByRoleAndStatusForUpdate(UserRole.ADMIN, UserStatus.ACTIVE)).thenReturn(List.of(lastAdmin));

		assertDenied(() -> accountService.update(USER_ID, new AccountChange("박원장", UserRole.STAFF, 1L)),
				AuthErrorCode.LAST_ADMIN);

		assertThat(lastAdmin.getRole()).isEqualTo(UserRole.ADMIN);
	}

	@Test
	void 다른_활성_관리자가_있으면_관리자를_차단할_수_있다() {
		User target = existing(account(USER_ID, UserRole.ADMIN, null));
		when(users.findAllByRoleAndStatusForUpdate(UserRole.ADMIN, UserStatus.ACTIVE))
			.thenReturn(List.of(account(ACTOR_ID, UserRole.ADMIN, null), target));

		accountService.disable(USER_ID);

		assertThat(target.isActive()).isFalse();
	}

	// 관리자 수를 줄일 수 있는 변경은 활성 관리자를 먼저 잠근다. 순서가 요청마다 다르면 서로 물려 멈춘다.
	@Test
	void 활성_관리자를_먼저_잠그고_그_계정을_잠근다() {
		existing(account(USER_ID, UserRole.STAFF, 1L));

		accountService.disable(USER_ID);
		accountService.update(USER_ID, new AccountChange("김직원", UserRole.STAFF, 2L));

		InOrder order = inOrder(users);
		order.verify(users).findAllByRoleAndStatusForUpdate(UserRole.ADMIN, UserStatus.ACTIVE);
		order.verify(users).findByIdForUpdate(USER_ID);
		order.verify(users).findAllByRoleAndStatusForUpdate(UserRole.ADMIN, UserStatus.ACTIVE);
		order.verify(users).findByIdForUpdate(USER_ID);
	}

	// 세션에는 역할과 지점이 들어 있다. 끊지 않으면 옛 지점의 권한으로 계속 일한다.
	@Test
	void 지점을_바꾸면_커밋한_뒤에_세션을_끊는다() {
		User user = existing(account(USER_ID, UserRole.STAFF, 1L));

		AccountView updated = accountService.update(USER_ID, new AccountChange("김직원", UserRole.STAFF, 2L));

		assertThat(updated.campusId()).isEqualTo(2L);
		assertThat(user.getCampusId()).isEqualTo(2L);
		InOrder order = inOrder(transactionManager, userSessions);
		order.verify(transactionManager).commit(any());
		order.verify(userSessions).terminateAll(USER_ID);
		assertThat(recorded()).isEqualTo(Kind.UPDATED);
	}

	// 이름은 권한과 무관하다. 일하던 사람을 내보내지 않는다. 끊는 것은 역할·지점이 지금과 다른 세션뿐이다.
	@Test
	void 이름만_고치면_지금의_역할과_지점인_세션은_남는다() {
		existing(account(USER_ID, UserRole.STAFF, 1L));

		AccountView updated = accountService.update(USER_ID, new AccountChange("김데스크", UserRole.STAFF, 1L));

		assertThat(updated.name()).isEqualTo("김데스크");
		verify(userSessions).terminateOutdated(USER_ID, UserRole.STAFF, 1L);
		verify(userSessions, never()).terminateAll(USER_ID);
		assertThat(recorded()).isEqualTo(Kind.UPDATED);
	}

	// 앞선 요청이 지점을 바꾸고 세션을 끊다가 실패했을 수 있다. 같은 요청을 다시 보내면 바뀐 것은 없지만 남은 옛 세션은 끊겨야 한다.
	@Test
	void 바뀐_것이_없으면_기록하지_않지만_옛_세션은_다시_찾아_끊는다() {
		existing(account(USER_ID, UserRole.STAFF, 1L));

		accountService.update(USER_ID, new AccountChange("김직원", UserRole.STAFF, 1L));

		verifyNoInteractions(trail);
		verify(userSessions).terminateOutdated(USER_ID, UserRole.STAFF, 1L);
	}

	@Test
	void 자기_역할과_지점은_바꿀_수_없지만_자기_이름은_고칠_수_있다() {
		User self = existing(account(ACTOR_ID, UserRole.ADMIN, null));
		when(users.findByIdForUpdate(ACTOR_ID)).thenReturn(Optional.of(self));

		assertDenied(() -> accountService.update(ACTOR_ID, new AccountChange("박원장", UserRole.STAFF, 1L)),
				AuthErrorCode.SELF_CHANGE_NOT_ALLOWED);
		assertThat(accountService.update(ACTOR_ID, new AccountChange("박대표", UserRole.ADMIN, null)).name())
			.isEqualTo("박대표");
		verify(userSessions, never()).terminateAll(ACTOR_ID);
	}

	@Test
	void 연계_학원_계정은_역할을_바꿀_수_없고_이름은_고칠_수_있다() {
		User partner = User.create("partner", "{noop}not-a-real-hash", "연계학원", UserRole.PARTNER, null, 3L);
		ReflectionTestUtils.setField(partner, "id", USER_ID);
		existing(partner);

		assertDenied(() -> accountService.update(USER_ID, new AccountChange("연계학원", UserRole.STAFF, 1L)),
				AuthErrorCode.PARTNER_ACCOUNT_NOT_SUPPORTED);
		assertThat(accountService.update(USER_ID, new AccountChange("새 이름", UserRole.PARTNER, null)).name())
			.isEqualTo("새 이름");
		assertThat(partner.getRole()).isEqualTo(UserRole.PARTNER);
	}

	@Test
	void 직원이나_관리자를_연계_학원으로_바꿀_수_없다() {
		existing(account(USER_ID, UserRole.STAFF, 1L));

		assertDenied(() -> accountService.update(USER_ID, new AccountChange("김직원", UserRole.PARTNER, null)),
				AuthErrorCode.PARTNER_ACCOUNT_NOT_SUPPORTED);
	}

	@Test
	void 비밀번호를_다시_정하면_커밋한_뒤에_세션을_모두_끊는다() {
		User user = existing(account(USER_ID, UserRole.STAFF, 1L));
		when(users.findById(USER_ID)).thenReturn(Optional.of(user));
		when(passwordEncoder.encode("brand-new-password")).thenReturn("{noop}brand-new-hash");

		accountService.resetPassword(USER_ID, "brand-new-password");

		assertThat(user.getPasswordHash()).isEqualTo("{noop}brand-new-hash");
		InOrder order = inOrder(transactionManager, userSessions);
		order.verify(transactionManager).commit(any());
		order.verify(userSessions).terminateAll(USER_ID);
		assertThat(recorded()).isEqualTo(Kind.PASSWORD_RESET);
	}

	// 자기 비밀번호는 지금 비밀번호를 확인하는 길로만 바꾼다. 세션을 훔친 사람이 비밀번호를 모르고도 바꾸는 것을 막는다.
	@Test
	void 자기_비밀번호는_다시_정하기로_바꿀_수_없다() {
		assertDenied(() -> accountService.resetPassword(ACTOR_ID, "brand-new-password"),
				AuthErrorCode.SELF_CHANGE_NOT_ALLOWED);

		verifyNoInteractions(passwordEncoder, userSessions, transactionManager);
		assertThat(recorded()).isEqualTo(Kind.SELF_CHANGE_DENIED);
	}

	@Test
	void 규칙에_맞지_않는_비밀번호로는_다시_정할_수_없다() {
		User user = account(USER_ID, UserRole.STAFF, 1L);
		when(users.findById(USER_ID)).thenReturn(Optional.of(user));

		assertDenied(() -> accountService.resetPassword(USER_ID, "short"), AuthErrorCode.PASSWORD_NOT_ACCEPTABLE);
		assertDenied(() -> accountService.resetPassword(USER_ID, "가".repeat(25)), AuthErrorCode.PASSWORD_NOT_ACCEPTABLE);
		assertDenied(() -> accountService.resetPassword(USER_ID, user.getLoginId()),
				AuthErrorCode.PASSWORD_NOT_ACCEPTABLE);

		verifyNoInteractions(passwordEncoder, userSessions, transactionManager);
	}

	// 지금 비밀번호를 확인한 뒤에 비밀번호가 바뀌었다(관리자가 다시 정했다). 옛 비밀번호만 아는 요청이 그것을 덮어쓰면 안 된다.
	@Test
	void 지금_비밀번호를_확인한_뒤에_비밀번호가_바뀌었으면_바꾸지_않는다() {
		User verified = account(ACTOR_ID, UserRole.STAFF, 1L);
		User resetMeanwhile = account(ACTOR_ID, UserRole.STAFF, 1L);
		resetMeanwhile.changePassword("{noop}reset-by-admin");
		when(users.findById(ACTOR_ID)).thenReturn(Optional.of(verified));
		when(users.findByIdForUpdate(ACTOR_ID)).thenReturn(Optional.of(resetMeanwhile));
		when(passwordEncoder.matches("old-password-1", verified.getPasswordHash())).thenReturn(true);
		when(passwordEncoder.encode("new-password-1")).thenReturn("{noop}new-hash");

		assertDenied(() -> accountService.changeOwnPassword("old-password-1", "new-password-1", session("session")),
				AuthErrorCode.CURRENT_PASSWORD_MISMATCH);

		assertThat(resetMeanwhile.getPasswordHash()).isEqualTo("{noop}reset-by-admin");
		// 이 거부도 "지금 비밀번호가 틀렸다"로 남긴다. 적는 것은 트랜잭션이 끝난 뒤다.
		InOrder order = inOrder(transactionManager, trail);
		order.verify(transactionManager).rollback(any());
		order.verify(trail).denied(any(), any());
		assertThat(recorded()).isEqualTo(Kind.PASSWORD_CHANGE_MISMATCH);
		verifyNoInteractions(userSessions);
	}

	// 차단은 세션을 끊지만, 이미 들어와 처리 중이던 요청은 끝까지 간다. 그 요청이 비밀번호를 바꾸게 두지 않는다.
	@Test
	void 차단된_계정은_자기_비밀번호를_바꿀_수_없다() {
		User disabled = account(ACTOR_ID, UserRole.STAFF, 1L);
		disabled.disable();
		when(users.findById(ACTOR_ID)).thenReturn(Optional.of(disabled));

		assertDenied(() -> accountService.changeOwnPassword("old-password-1", "new-password-1", session("session")),
				CommonErrorCode.UNAUTHENTICATED);

		verifyNoInteractions(passwordEncoder, transactionManager);
		// 차단된 계정의 세션이 살아 있다는 뜻이다(차단할 때 끊지 못했다). 발견한 자리에서 끊는다.
		verify(userSessions).terminateAll(ACTOR_ID);
	}

	// 지금 비밀번호를 확인하는 동안(해시 계산) 차단됐다. 처음 읽을 때는 활성이었다.
	@Test
	void 지금_비밀번호를_확인하는_사이에_차단된_계정은_비밀번호를_바꿀_수_없다() {
		User active = account(ACTOR_ID, UserRole.STAFF, 1L);
		User disabledMeanwhile = account(ACTOR_ID, UserRole.STAFF, 1L);
		disabledMeanwhile.disable();
		when(users.findById(ACTOR_ID)).thenReturn(Optional.of(active));
		when(users.findByIdForUpdate(ACTOR_ID)).thenReturn(Optional.of(disabledMeanwhile));
		when(passwordEncoder.matches("old-password-1", active.getPasswordHash())).thenReturn(true);
		when(passwordEncoder.encode("new-password-1")).thenReturn("{noop}new-hash");

		assertDenied(() -> accountService.changeOwnPassword("old-password-1", "new-password-1", session("session")),
				CommonErrorCode.UNAUTHENTICATED);

		assertThat(disabledMeanwhile.getPasswordHash()).isEqualTo(active.getPasswordHash());
		verify(transactionManager).rollback(any());
		verifyNoInteractions(trail);
		verify(userSessions).terminateAll(ACTOR_ID);
		verify(userSessions, never()).terminateOthers(anyLong(), any());
	}

	@Test
	void 자기_비밀번호를_바꾸면_센_것을_되돌리고_커밋한_뒤_다른_세션만_끊는다() {
		User self = ownAccount();
		Permit permit = counted();
		when(passwordEncoder.encode("new-password-1")).thenReturn("{noop}new-hash");

		accountService.changeOwnPassword("old-password-1", "new-password-1", session("this-session"));

		assertThat(self.getPasswordHash()).isEqualTo("{noop}new-hash");
		verify(limiter).refund(permit);
		InOrder order = inOrder(transactionManager, userSessions);
		order.verify(transactionManager).commit(any());
		order.verify(userSessions).terminateOthers(ACTOR_ID, "this-session");
		verify(userSessions, never()).terminateAll(ACTOR_ID);
		assertThat(recorded()).isEqualTo(Kind.PASSWORD_CHANGED);
	}

	// 비밀번호를 바꾸면 권한 판 번호가 오른다 (ADR-0031). 지금 세션의 번호를 그대로 두면 바꾼 사람도 다음 요청에서 막힌다.
	@Test
	void 자기_비밀번호를_바꾸면_지금_세션에_새_권한_판_번호를_넣는다() {
		User self = ownAccount();
		counted();
		when(passwordEncoder.encode("new-password-1")).thenReturn("{noop}new-hash");
		MockHttpSession here = session("this-session");

		accountService.changeOwnPassword("old-password-1", "new-password-1", here);

		assertThat(self.getAuthVersion()).isEqualTo(1);
		assertThat(here.getAttribute(LoginService.AUTH_VERSION_ATTRIBUTE)).isEqualTo(1L);
	}

	// 번호를 넣는 것이 다른 세션을 끊는 것보다 먼저다. 순서가 반대면 끊다가 실패했을 때 바꾼 사람의 세션까지 막힌다.
	@Test
	void 다른_세션을_끊지_못해도_지금_세션에는_새_권한_판_번호가_들어_있다() {
		ownAccount();
		counted();
		when(passwordEncoder.encode("new-password-1")).thenReturn("{noop}new-hash");
		when(userSessions.terminateOthers(ACTOR_ID, "this-session")).thenThrow(new IllegalStateException("저장소 장애"));
		MockHttpSession here = session("this-session");

		catchThrowable(() -> accountService.changeOwnPassword("old-password-1", "new-password-1", here));

		assertThat(here.getAttribute(LoginService.AUTH_VERSION_ATTRIBUTE)).isEqualTo(1L);
	}

	@Test
	void 비밀번호를_바꾸지_못했으면_지금_세션의_권한_판_번호를_건드리지_않는다() {
		ownAccount();
		counted();
		MockHttpSession here = session("this-session");

		assertDenied(() -> accountService.changeOwnPassword("wrong-password-1", "new-password-1", here),
				AuthErrorCode.CURRENT_PASSWORD_MISMATCH);

		assertThat(here.getAttribute(LoginService.AUTH_VERSION_ATTRIBUTE)).isNull();
	}

	// 틀린 것은 센 채로 둔다. 되돌리면 대입에 횟수가 들지 않는다.
	@Test
	void 지금_비밀번호가_틀리면_센_것을_되돌리지_않고_거부를_적는다() {
		ownAccount();
		counted();

		assertDenied(() -> accountService.changeOwnPassword("wrong-password-1", "new-password-1", session("this-session")),
				AuthErrorCode.CURRENT_PASSWORD_MISMATCH);

		verify(limiter, never()).refund(any());
		assertThat(recorded()).isEqualTo(Kind.PASSWORD_CHANGE_MISMATCH);
		verifyNoInteractions(transactionManager, userSessions);
	}

	// 되돌리지 못해도 횟수가 더 남을 뿐이다. 맞는 비밀번호를 넣은 사람의 변경을 그것 때문에 멈추지 않는다.
	@Test
	void 센_것을_되돌리지_못해도_비밀번호는_바꾼다() {
		User self = ownAccount();
		Permit permit = counted();
		doThrow(new IllegalStateException("저장소 장애")).when(limiter).refund(permit);
		when(passwordEncoder.encode("new-password-1")).thenReturn("{noop}new-hash");

		accountService.changeOwnPassword("old-password-1", "new-password-1", session("this-session"));

		assertThat(self.getPasswordHash()).isEqualTo("{noop}new-hash");
		verify(userSessions).terminateOthers(ACTOR_ID, "this-session");
	}

	@Test
	void 로그인한_계정을_알_수_없으면_비밀번호를_바꿀_수_없다() {
		when(currentActor.get()).thenReturn(Actor.none());

		assertThatIllegalStateException()
			.isThrownBy(() -> accountService.changeOwnPassword("old-password-1", "new-password-1", session("this-session")));

		verifyNoInteractions(limiter, users, userSessions, transactionManager);
	}

	// 비밀번호는 바뀌었는데 다른 세션을 끊지 못했다. 같은 요청을 다시 보내면 지금 비밀번호가 맞지 않아 거부되므로,
	// 일반 오류가 아니라 "바뀌었다, 새 비밀번호로 한 번 더 하라"고 알린다.
	@Test
	void 자기_비밀번호를_바꾼_뒤_다른_세션을_끊지_못하면_바뀌었다는_것과_할_일을_알린다() {
		User self = ownAccount();
		counted();
		when(passwordEncoder.encode("new-password-1")).thenReturn("{noop}new-hash");
		IllegalStateException failure = new IllegalStateException("저장소 장애");
		when(userSessions.terminateOthers(ACTOR_ID, "this-session")).thenThrow(failure);

		assertThatThrownBy(() -> accountService.changeOwnPassword("old-password-1", "new-password-1", session("this-session")))
			.isInstanceOfSatisfying(BusinessException.class, ex -> {
				assertThat(ex.getErrorCode()).isEqualTo(AuthErrorCode.SESSION_TERMINATION_FAILED);
				assertThat(ex).hasMessageContaining("비밀번호는 바뀌었지만").hasMessageContaining("새 비밀번호로").hasCause(failure);
			});

		assertThat(self.getPasswordHash()).isEqualTo("{noop}new-hash");
		verify(transactionManager).commit(any());
	}

	// 위의 경우에서 다시 보내는 길이다. 새 비밀번호를 지금 비밀번호 자리에 넣으면 바꿀 것은 없지만, 거부하기 전에 다른 세션을 끊는다.
	@Test
	void 새_비밀번호가_지금과_같으면_거부하되_다른_세션은_끊는다() {
		User self = ownAccount();
		counted();
		when(passwordEncoder.matches("old-password-1", self.getPasswordHash())).thenReturn(true);

		assertDenied(() -> accountService.changeOwnPassword("old-password-1", "old-password-1", session("this-session")),
				AuthErrorCode.PASSWORD_UNCHANGED);

		verify(userSessions).terminateOthers(ACTOR_ID, "this-session");
		verifyNoInteractions(transactionManager, trail);
	}

	// 차단은 반영됐는데 세션을 끊지 못했다. 일반 오류로 답하면 관리자는 "실패했다"고 읽고, 목록에는 차단된 것으로 보인다.
	@Test
	void 차단한_뒤_세션을_끊지_못하면_저장은_됐다는_것을_알린다() {
		User user = existing(account(USER_ID, UserRole.STAFF, 1L));
		IllegalStateException failure = new IllegalStateException("저장소 장애");
		when(userSessions.terminateAll(USER_ID)).thenThrow(failure);

		assertThatThrownBy(() -> accountService.disable(USER_ID)).isInstanceOfSatisfying(BusinessException.class, ex -> {
			assertThat(ex.getErrorCode()).isEqualTo(AuthErrorCode.SESSION_TERMINATION_FAILED);
			assertThat(ex).hasCause(failure);
		});

		assertThat(user.isActive()).isFalse();
		verify(transactionManager).commit(any());
		assertThat(recorded()).isEqualTo(Kind.DISABLED);
	}

	@Test
	void 고치거나_비밀번호를_다시_정한_뒤_세션을_끊지_못해도_같은_코드로_알린다() {
		User user = existing(account(USER_ID, UserRole.STAFF, 1L));
		when(users.findById(USER_ID)).thenReturn(Optional.of(user));
		when(passwordEncoder.encode("brand-new-password")).thenReturn("{noop}brand-new-hash");
		when(userSessions.terminateAll(USER_ID)).thenThrow(new IllegalStateException("저장소 장애"));
		when(userSessions.terminateOutdated(USER_ID, UserRole.STAFF, 2L)).thenThrow(new IllegalStateException("저장소 장애"));

		assertDenied(() -> accountService.update(USER_ID, new AccountChange("김직원", UserRole.STAFF, 2L)),
				AuthErrorCode.SESSION_TERMINATION_FAILED);
		assertDenied(() -> accountService.update(USER_ID, new AccountChange("김직원", UserRole.STAFF, 2L)),
				AuthErrorCode.SESSION_TERMINATION_FAILED);
		assertDenied(() -> accountService.resetPassword(USER_ID, "brand-new-password"),
				AuthErrorCode.SESSION_TERMINATION_FAILED);

		assertThat(user.getCampusId()).isEqualTo(2L);
		assertThat(user.getPasswordHash()).isEqualTo("{noop}brand-new-hash");
	}

	// 같은 아이디로 동시에 만들면 미리 보는 확인을 둘 다 통과하고 유니크 제약에서 하나가 걸린다.
	@Test
	void 아이디의_유니크_제약에_걸리면_아이디_중복이고_거부를_트랜잭션_뒤에_적는다() {
		when(passwordEncoder.encode("desk-password-123")).thenReturn("{noop}desk-hash");
		when(users.findByLoginId("desk")).thenReturn(Optional.empty());
		when(users.saveAndFlush(any())).thenThrow(violationOf("core_user.uk_core_user_login_id"));

		assertDenied(() -> accountService
			.create(new NewAccount("desk", "김직원", "desk-password-123", UserRole.STAFF, 1L)),
				AuthErrorCode.LOGIN_ID_DUPLICATED);

		InOrder order = inOrder(transactionManager, trail);
		order.verify(transactionManager).rollback(any());
		order.verify(trail).denied(any(), any());
		assertThat(recorded()).isEqualTo(Kind.LOGIN_ID_DUPLICATED);
	}

	@Test
	void 지점의_외래_키에_걸리면_없는_지점이다() {
		when(passwordEncoder.encode("desk-password-123")).thenReturn("{noop}desk-hash");
		when(users.findByLoginId("desk")).thenReturn(Optional.empty());
		DataIntegrityViolationException violation = violationOf("fk_core_user_campus");
		when(users.saveAndFlush(any())).thenThrow(violation);

		assertThatThrownBy(
				() -> accountService.create(new NewAccount("desk", "김직원", "desk-password-123", UserRole.STAFF, 9L)))
			.isInstanceOfSatisfying(BusinessException.class, ex -> {
				assertThat(ex.getErrorCode()).isEqualTo(AuthErrorCode.CAMPUS_NOT_FOUND);
				// 원래 예외를 버리지 않는다.
				assertThat(ex).hasCause(violation);
			});
		verifyNoInteractions(trail);
	}

	// 다른 제약에 걸린 것을 "없는 지점"이나 "아이디 중복"으로 답하면 진짜 원인이 묻힌다. 서버 오류로 드러나야 한다.
	@Test
	void 다른_제약에_걸린_저장은_그대로_실패한다() {
		when(passwordEncoder.encode("desk-password-123")).thenReturn("{noop}desk-hash");
		when(users.findByLoginId("desk")).thenReturn(Optional.empty());
		DataIntegrityViolationException other = violationOf("core_audit_log.some_other_constraint");
		DataIntegrityViolationException unnamed = new DataIntegrityViolationException("Data too long for column");
		when(users.saveAndFlush(any())).thenThrow(other, unnamed);
		User staff = existing(account(USER_ID, UserRole.STAFF, 1L));
		doThrow(other).when(users).flush();

		assertThatThrownBy(
				() -> accountService.create(new NewAccount("desk", "김직원", "desk-password-123", UserRole.STAFF, 1L)))
			.isSameAs(other);
		assertThatThrownBy(
				() -> accountService.create(new NewAccount("desk", "김직원", "desk-password-123", UserRole.STAFF, 1L)))
			.isSameAs(unnamed);
		// 이름만 고치는 요청도 지점 값을 싣고 있다. 그 값이 있다고 "없는 지점"이 되면 안 된다.
		assertThatThrownBy(() -> accountService.update(USER_ID, new AccountChange("김데스크", UserRole.STAFF, 1L)))
			.isSameAs(other);

		assertThat(staff.getCampusId()).isEqualTo(1L);
		verify(trail, never()).denied(any(), any());
		verifyNoInteractions(userSessions);
	}

	@Test
	void 지점을_옮기다_외래_키에_걸리면_없는_지점이다() {
		existing(account(USER_ID, UserRole.STAFF, 1L));
		doThrow(violationOf("fk_core_user_campus")).when(users).flush();

		assertDenied(() -> accountService.update(USER_ID, new AccountChange("김직원", UserRole.STAFF, 9L)),
				AuthErrorCode.CAMPUS_NOT_FOUND);

		verifyNoInteractions(trail, userSessions);
	}

	@Test
	void 없는_계정은_고치거나_풀거나_비밀번호를_다시_정할_수_없다() {
		when(users.findByIdForUpdate(USER_ID)).thenReturn(Optional.empty());
		when(users.findById(USER_ID)).thenReturn(Optional.empty());

		assertDenied(() -> accountService.update(USER_ID, new AccountChange("김직원", UserRole.STAFF, 1L)),
				CommonErrorCode.NOT_FOUND);
		assertDenied(() -> accountService.enable(USER_ID), CommonErrorCode.NOT_FOUND);
		assertDenied(() -> accountService.resetPassword(USER_ID, "brand-new-password"), CommonErrorCode.NOT_FOUND);
		assertDenied(() -> accountService.get(USER_ID), CommonErrorCode.NOT_FOUND);

		verifyNoInteractions(passwordEncoder, userSessions, trail);
	}

	// 다른 활성 관리자가 있으면 관리자를 직원으로 내릴 수 있다. 내리면 관리자 권한의 세션이 남지 않게 모두 끊는다.
	@Test
	void 다른_활성_관리자가_있으면_관리자를_직원으로_내리고_세션을_모두_끊는다() {
		User target = existing(account(USER_ID, UserRole.ADMIN, null));
		when(users.findAllByRoleAndStatusForUpdate(UserRole.ADMIN, UserStatus.ACTIVE))
			.thenReturn(List.of(account(ACTOR_ID, UserRole.ADMIN, null), target));

		AccountView demoted = accountService.update(USER_ID, new AccountChange("박원장", UserRole.STAFF, 1L));

		assertThat(demoted.role()).isEqualTo(UserRole.STAFF);
		assertThat(target.getRole()).isEqualTo(UserRole.STAFF);
		verify(userSessions).terminateAll(USER_ID);
	}

	// 자기 계정(ACTOR_ID)으로 로그인한 직원. 지금 비밀번호는 "old-password-1"이다.
	private User ownAccount() {
		User self = account(ACTOR_ID, UserRole.STAFF, 1L);
		when(users.findById(ACTOR_ID)).thenReturn(Optional.of(self));
		when(users.findByIdForUpdate(ACTOR_ID)).thenReturn(Optional.of(self));
		when(passwordEncoder.matches("old-password-1", self.getPasswordHash())).thenReturn(true);
		return self;
	}

	// 횟수를 세고 받은 표. 되돌릴 때 이것을 넘긴다.
	private Permit counted() {
		Permit permit = new Permit(new RateLimit("auth.password-change", 5, Duration.ofMinutes(15)),
				(Actor.User) Actor.user(ACTOR_ID), "window-1");
		when(limiter.acquire(any(), any(), any(), any())).thenReturn(permit);
		return permit;
	}

	private static DataIntegrityViolationException violationOf(String constraintName) {
		return new DataIntegrityViolationException("could not execute statement",
				new ConstraintViolationException("constraint violated", new SQLException("violated"), constraintName));
	}

	private User existing(User user) {
		when(users.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
		return user;
	}

	private static User account(long id, UserRole role, Long campusId) {
		User user = User.create("account-number-" + id, "{noop}not-a-real-hash", (role == UserRole.ADMIN) ? "박원장" : "김직원",
				role, campusId, null);
		ReflectionTestUtils.setField(user, "id", id);
		return user;
	}

	// 기록하는 쪽에 마지막으로 넘긴 일. 한 일은 done으로, 거부된 시도는 denied로 넘어갔는지도 함께 본다.
	private Kind recorded() {
		ArgumentCaptor<AccountActivity> done = ArgumentCaptor.forClass(AccountActivity.class);
		ArgumentCaptor<AccountActivity> denied = ArgumentCaptor.forClass(AccountActivity.class);
		verify(trail, atLeast(0)).done(done.capture());
		verify(trail, atLeast(0)).denied(denied.capture(), any());
		assertThat(done.getAllValues()).allSatisfy(activity -> assertThat(activity.kind().denied()).isFalse());
		assertThat(denied.getAllValues()).allSatisfy(activity -> assertThat(activity.kind().denied()).isTrue());
		List<AccountActivity> all = new ArrayList<>(done.getAllValues());
		all.addAll(denied.getAllValues());
		assertThat(all).as("기록하는 쪽에 넘긴 일").hasSize(1);
		return all.get(0).kind();
	}

	private static void assertDenied(Runnable call, ErrorCode expected) {
		assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
				ex -> assertThat(ex.getErrorCode()).isEqualTo(expected));
	}

	private static MockHttpSession session(String id) {
		return new MockHttpSession(null, id);
	}

}
