package com.jey.core.auth;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.jey.core.auth.api.AccountActivity;
import com.jey.core.auth.api.AccountActivity.Kind;
import com.jey.core.auth.api.AccountAuditTrail;
import com.jey.core.auth.api.AccountRateLimiter;
import com.jey.core.auth.api.CurrentActor;
import com.jey.core.auth.api.UserRole;
import com.jey.core.auth.domain.User;
import com.jey.core.auth.domain.UserRepository;
import com.jey.core.auth.domain.UserStatus;
import com.jey.core.shared.Actor;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.CommonErrorCode;
import com.jey.core.shared.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
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
			.isThrownBy(() -> accountService.changeOwnPassword("old-password-1", "new-password-1", "session"));
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
		order.verify(userSessions).terminateOutdated(USER_ID, UserRole.STAFF, 2L);
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

		assertDenied(() -> accountService.changeOwnPassword("old-password-1", "new-password-1", "session"),
				AuthErrorCode.CURRENT_PASSWORD_MISMATCH);

		assertThat(resetMeanwhile.getPasswordHash()).isEqualTo("{noop}reset-by-admin");
		verify(transactionManager).rollback(any());
		verify(trail, never()).done(any());
		verifyNoInteractions(userSessions);
	}

	// 차단은 세션을 끊지만, 이미 들어와 처리 중이던 요청은 끝까지 간다. 그 요청이 비밀번호를 바꾸게 두지 않는다.
	@Test
	void 차단된_계정은_자기_비밀번호를_바꿀_수_없다() {
		User disabled = account(ACTOR_ID, UserRole.STAFF, 1L);
		disabled.disable();
		when(users.findById(ACTOR_ID)).thenReturn(Optional.of(disabled));

		assertDenied(() -> accountService.changeOwnPassword("old-password-1", "new-password-1", "session"),
				CommonErrorCode.UNAUTHENTICATED);

		verifyNoInteractions(passwordEncoder, userSessions, transactionManager);
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

}
