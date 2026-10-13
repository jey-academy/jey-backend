package com.jey.core.auth;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

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
import com.jey.core.shared.UniqueKeys;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 계정을 만들고, 고치고, 막는다 (ADR-0030).
 *
 * <p>계정을 바꾸는 메서드는 같은 순서로 일한다: 트랜잭션에서 고치고, 커밋한 뒤, 그 계정의 세션을 끊는다
 * (차단을 푸는 것만 끊을 세션이 없다).
 * 순서가 반대면, 세션을 끊은 직후 변경이 반영되기 전에 다시 로그인한 세션이 옛 권한으로 살아남는다.
 * 그래서 바깥 트랜잭션 안에서는 부를 수 없다(합류하면 커밋되기 전에 세션을 끊게 된다).
 * 거부된 시도를 감사 로그에 적는 것도 트랜잭션이 끝난 뒤여야 한다.
 *
 * <p>세션을 끊는 것은 커밋한 뒤의 별도 단계라서 따로 실패할 수 있다(세션 저장소 장애). 그때는 계정이 이미 바뀌어 있다.
 * 일반 오류로 답하면 부른 쪽이 "실패했다"고 읽으므로, {@code SESSION_TERMINATION_FAILED}로 "저장은 됐고 로그아웃만 못 시켰다"고 알린다.
 *
 * <p>감사 로그와 지점 목록은 직접 부르지 않는다(둘 다 이 패키지를 쓰고 있어 서로 물린다).
 * 기록은 {@link AccountAuditTrail}에 맡기고, 없는 지점은 DB의 외래 키가 걸러낸 것을 받아 알린다.
 *
 * <p>비밀번호 원문은 해시하는 데만 쓰고 로그, 예외 문구, 감사 로그에 넣지 않는다.
 */
@Service
@EnableConfigurationProperties(PasswordChangeLimitProperties.class)
public class AccountService {

	private static final Logger log = LoggerFactory.getLogger(AccountService.class);

	// 마이그레이션에 적은 제약 이름. 저장이 어느 제약에 걸렸는지 이것으로 가린다.
	private static final String LOGIN_ID_UNIQUE_KEY = "uk_core_user_login_id";

	private static final String CAMPUS_FOREIGN_KEY = "fk_core_user_campus";

	private final UserRepository users;

	private final UserSessions userSessions;

	private final TransactionTemplate transaction;

	private final PasswordEncoder passwordEncoder;

	private final AccountAuditTrail trail;

	private final CurrentActor currentActor;

	private final AccountRateLimiter limiter;

	private final RateLimit passwordChangeLimit;

	AccountService(UserRepository users, UserSessions userSessions, TransactionTemplate transaction,
			PasswordEncoder passwordEncoder, AccountAuditTrail trail, CurrentActor currentActor,
			AccountRateLimiter limiter, PasswordChangeLimitProperties passwordChangeLimit) {
		this.users = users;
		this.userSessions = userSessions;
		this.transaction = transaction;
		this.passwordEncoder = passwordEncoder;
		this.trail = trail;
		this.currentActor = currentActor;
		this.limiter = limiter;
		this.passwordChangeLimit = passwordChangeLimit.toRateLimit();
	}

	/**
	 * 조건은 모두 선택이다. 아이디 순으로 준다.
	 *
	 * @param page 0부터
	 */
	public Page<AccountView> search(UserRole role, Long campusId, UserStatus status, int page, int size) {
		return users.search(role, campusId, status, PageRequest.of(page, size, Sort.by("loginId")))
			.map(AccountView::from);
	}

	/**
	 * @throws BusinessException 계정이 없을 때
	 */
	public AccountView get(long userId) {
		return users.findById(userId).map(AccountView::from).orElseThrow(AccountService::notFound);
	}

	/**
	 * 관리자나 직원 계정을 만든다. 연계 학원 계정은 만들 수 없다(연계 학원이 생기는 작업에서 만든다).
	 *
	 * @throws BusinessException 연계 학원 역할이거나, 역할과 지점의 조합이 맞지 않거나, 없는 지점이거나,
	 * 비밀번호가 규칙에 맞지 않거나, 이미 쓰는 아이디일 때
	 * @throws IllegalStateException 다른 트랜잭션 안에서 호출했을 때
	 */
	public AccountView create(NewAccount account) {
		requireNoTransaction("create");
		requireManageable(account.role());
		requireMatching(account.role(), account.campusId());
		String loginId = User.normalizeLoginId(account.loginId());
		requireAcceptable(account.password(), loginId);
		// 해시는 느리다. DB 연결을 쥐기 전에 계산한다.
		String passwordHash = passwordEncoder.encode(account.password());
		return recordingDenial(null, () -> {
			try {
				return transaction.execute(status -> {
					if (users.findByLoginId(loginId).isPresent()) {
						throw new BusinessException(AuthErrorCode.LOGIN_ID_DUPLICATED);
					}
					User saved = users.saveAndFlush(User.create(loginId, passwordHash, account.name(), account.role(),
							account.campusId(), null));
					trail.done(AccountActivity.created(saved.getId(), saved.getRole(), saved.getCampusId()));
					return AccountView.from(saved);
				});
			}
			catch (DataIntegrityViolationException ex) {
				// 같은 아이디로 동시에 만들면 위의 확인을 둘 다 통과하고 유니크 제약에서 하나가 걸린다.
				if (UniqueKeys.isViolationOf(ex, LOGIN_ID_UNIQUE_KEY)) {
					throw new BusinessException(AuthErrorCode.LOGIN_ID_DUPLICATED, null, ex);
				}
				throw campusNotFoundOr(ex);
			}
		});
	}

	/**
	 * 이름, 역할, 소속 지점을 고친다. 역할이나 지점이 바뀌면 그 계정의 세션을 모두 끊는다(세션에 로그인할 때의 역할과 지점이 들어 있다).
	 * 이름만 바뀌면 끊기는 세션이 없다. 세션에 든 이름은 다음 로그인 때 바뀐다.
	 *
	 * <p>세션을 끊는 데 실패하면 {@code SESSION_TERMINATION_FAILED}가 난다. 그때 변경은 이미 반영돼 있다.
	 * 같은 내용으로 다시 고치면 "바뀐 것이 없다"로 끝나지만, 그때도 역할·지점이 지금과 다른 세션을 찾아 끊으므로 남아 있던 옛 세션은 끊긴다.
	 *
	 * @throws BusinessException 계정이 없거나, 연계 학원 역할과 얽힌 변경이거나, 역할과 지점의 조합이 맞지 않거나, 없는 지점이거나,
	 * 자기 자신의 역할·지점을 바꾸려 하거나, 마지막 남은 활성 관리자를 다른 역할로 바꾸려 할 때.
	 * 고친 뒤 세션을 끊지 못했을 때도 난다(변경은 반영돼 있다)
	 * @throws IllegalStateException 다른 트랜잭션 안에서 호출했을 때
	 */
	public AccountView update(long userId, AccountChange change) {
		requireNoTransaction("update");
		Updated updated = recordingDenial(userId, () -> {
			try {
				return updateInTransaction(userId, change);
			}
			catch (DataIntegrityViolationException ex) {
				throw campusNotFoundOr(ex);
			}
		});
		AccountView account = updated.account();
		if (updated.affiliationChanged()) {
			// 권한이 바뀌었다. 세션에 무엇이 들었는지 따지지 않고 모두 끊는다.
			afterCommit(() -> userSessions.terminateAll(userId));
		}
		else {
			// 바뀐 것이 없어도 옛 권한의 세션이 남아 있을 수 있다(앞선 요청이 고치기만 하고 세션을 끊지 못했다). 그런 세션만 찾아 끊는다.
			afterCommit(() -> userSessions.terminateOutdated(userId, account.role(), account.campusId()));
		}
		return account;
	}

	private Updated updateInTransaction(long userId, AccountChange change) {
		return transaction.execute(status -> {
			List<User> activeAdmins = lockActiveAdmins();
			User user = users.findByIdForUpdate(userId).orElseThrow(AccountService::notFound);
			boolean affiliationChanges = change.role() != user.getRole()
					|| !Objects.equals(change.campusId(), user.getCampusId());
			if (affiliationChanges) {
				// 연계 학원 계정은 연계 학원 식별자와 묶여 있다. 여기서는 역할을 그쪽으로도 그쪽에서도 옮기지 않는다.
				requireManageable(change.role());
				requireManageable(user.getRole());
				requireMatching(change.role(), change.campusId());
				if (isSelf(userId)) {
					throw new BusinessException(AuthErrorCode.SELF_CHANGE_NOT_ALLOWED);
				}
				if (change.role() != UserRole.ADMIN) {
					requireAnotherActiveAdmin(activeAdmins, user);
				}
			}
			boolean nameChanged = user.rename(change.name());
			boolean affiliationChanged = user.changeAffiliation(change.role(), change.campusId());
			if (nameChanged || affiliationChanged) {
				// 없는 지점이면 여기서 외래 키에 걸린다. 커밋 때까지 미루면 기록을 적은 뒤에야 드러난다.
				users.flush();
				trail.done(AccountActivity.updated(userId, user.getRole(), user.getCampusId(), nameChanged,
						affiliationChanged));
			}
			return new Updated(AccountView.from(user), affiliationChanged);
		});
	}

	/**
	 * 계정을 차단한다. 다음 로그인을 막고, 이미 로그인된 세션도 모두 끊는다.
	 * 이미 처리 중이던 요청은 끝까지 처리된다. 세션을 끊는 데 실패하면 {@code SESSION_TERMINATION_FAILED}가 나며,
	 * 다시 호출해도 안전하다(이미 차단된 계정이어도 세션을 다시 끊는다).
	 *
	 * @throws BusinessException 계정이 없거나, 자기 자신이거나, 마지막 남은 활성 관리자일 때. 차단한 뒤 세션을 끊지 못했을 때도 난다(차단은 반영돼 있다)
	 * @throws IllegalStateException 다른 트랜잭션 안에서 호출했을 때
	 */
	public AccountView disable(long userId) {
		// 바깥 트랜잭션에 합류하면 차단이 DB에 반영되기 전에 세션을 끊게 되어 아래 순서가 깨진다.
		requireNoTransaction("disable");
		AccountView disabled = recordingDenial(userId, () -> transaction.execute(status -> {
			List<User> activeAdmins = lockActiveAdmins();
			User user = users.findByIdForUpdate(userId).orElseThrow(AccountService::notFound);
			if (isSelf(userId)) {
				throw new BusinessException(AuthErrorCode.SELF_CHANGE_NOT_ALLOWED);
			}
			requireAnotherActiveAdmin(activeAdmins, user);
			if (user.isActive()) {
				user.disable();
				trail.done(AccountActivity.of(Kind.DISABLED, userId));
			}
			return AccountView.from(user);
		}));
		// 차단이 DB에 반영된 뒤에 세션을 끊는다. 순서가 반대면, 세션을 끊은 직후 반영 전에 다시 로그인한 세션이 살아남는다.
		// 이 순서로도 틈이 완전히 닫히지는 않는다. 차단 직전에 로그인을 시작한 요청이 세션을 늦게 저장하면 살아남을 수 있어서,
		// LoginService가 세션을 만들기 직전에 계정 상태를 다시 읽어 그 틈을 수 밀리초로 줄인다(ADR-0019).
		afterCommit(() -> userSessions.terminateAll(userId));
		return disabled;
	}

	/**
	 * 차단을 푼다. 다시 로그인할 수 있다.
	 *
	 * @throws BusinessException 계정이 없을 때
	 * @throws IllegalStateException 다른 트랜잭션 안에서 호출했을 때
	 */
	public AccountView enable(long userId) {
		requireNoTransaction("enable");
		return transaction.execute(status -> {
			User user = users.findByIdForUpdate(userId).orElseThrow(AccountService::notFound);
			if (!user.isActive()) {
				user.enable();
				trail.done(AccountActivity.of(Kind.ENABLED, userId));
			}
			return AccountView.from(user);
		});
	}

	/**
	 * 관리자가 다른 계정의 비밀번호를 다시 정한다. 그 계정의 세션을 모두 끊는다. 차단된 계정은 차단된 채로 비밀번호만 바뀐다.
	 * 자기 비밀번호는 지금 비밀번호를 확인하는 {@link #changeOwnPassword}로 바꾼다.
	 *
	 * @throws BusinessException 계정이 없거나, 자기 자신이거나, 비밀번호가 규칙에 맞지 않을 때.
	 * 바꾼 뒤 세션을 끊지 못했을 때도 난다(비밀번호는 바뀌어 있다. 다시 부르면 끊는다)
	 * @throws IllegalStateException 다른 트랜잭션 안에서 호출했을 때
	 */
	public void resetPassword(long userId, String newPassword) {
		requireNoTransaction("resetPassword");
		recordingDenial(userId, () -> {
			if (isSelf(userId)) {
				throw new BusinessException(AuthErrorCode.SELF_CHANGE_NOT_ALLOWED);
			}
			// 아이디는 바뀌지 않는다. 잠그기 전에 읽어도 된다.
			User found = users.findById(userId).orElseThrow(AccountService::notFound);
			requireAcceptable(newPassword, found.getLoginId());
			String passwordHash = passwordEncoder.encode(newPassword);
			transaction.executeWithoutResult(status -> {
				User user = users.findByIdForUpdate(userId).orElseThrow(AccountService::notFound);
				user.changePassword(passwordHash);
				trail.done(AccountActivity.of(Kind.PASSWORD_RESET, userId));
			});
			return null;
		});
		afterCommit(() -> userSessions.terminateAll(userId));
	}

	/**
	 * 로그인한 사람이 자기 비밀번호를 바꾼다. 지금 세션은 남기고 그 계정의 다른 세션을 모두 끊는다.
	 *
	 * <p>비밀번호를 바꾸면 계정의 권한 판 번호가 오른다(ADR-0031). 지금 세션에는 커밋한 직후에 새 번호를 넣는다.
	 * 넣지 못하고 요청이 끝나면 지금 세션도 다음 요청에서 막힌다. 새 비밀번호로 다시 로그인하면 된다.
	 *
	 * <p>지금 비밀번호를 틀린 횟수를 계정별로 센다. 한도를 넘으면 맞는 비밀번호로도 구간이 끝날 때까지 바꿀 수 없다.
	 * 로그인된 세션을 손에 넣은 사람이 지금 비밀번호를 대입으로 알아내는 것을 느리게 한다. 지금 비밀번호가 맞은 요청은 세지 않는다.
	 *
	 * <p>다른 세션을 끊다 실패하면 {@code SESSION_TERMINATION_FAILED}가 난다. 그때 비밀번호는 이미 바뀌어 있어서 같은 요청을 다시 보내면
	 * "지금 비밀번호가 틀리다"로 거부된다. 새 비밀번호를 지금 비밀번호 자리에 넣어 다시 보내면 된다:
	 * 새 비밀번호가 지금 비밀번호와 같은 요청은 바꿀 것이 없어 거부되지만, 거부하기 전에 다른 세션을 끊는다.
	 *
	 * @param currentSession 이 요청의 세션. 이 세션은 끊지 않는다
	 * @throws com.jey.core.shared.RateLimitedException 틀린 횟수가 한도를 넘었을 때
	 * @throws BusinessException 지금 비밀번호가 틀리거나, 새 비밀번호가 규칙에 맞지 않거나, 지금 비밀번호와 같을 때.
	 * 바꾼 뒤 다른 세션을 끊지 못했을 때도 난다(비밀번호는 바뀌어 있다)
	 * @throws IllegalStateException 로그인한 계정을 알 수 없거나, 다른 트랜잭션 안에서 호출했을 때
	 */
	public void changeOwnPassword(String currentPassword, String newPassword, HttpSession currentSession) {
		requireNoTransaction("changeOwnPassword");
		Objects.requireNonNull(currentSession, "currentSession");
		String currentSessionId = currentSession.getId();
		if (!(currentActor.get() instanceof Actor.User self)) {
			throw new IllegalStateException("로그인한 계정 없이 비밀번호를 바꿀 수 없다");
		}
		long userId = self.accountId();
		// 확인하기 전에 센다. 확인한 뒤에 세면 동시에 보낸 요청이 모두 한도 확인을 통과한다.
		Permit permit = limiter.acquire(passwordChangeLimit, self, AuthErrorCode.TOO_MANY_PASSWORD_ATTEMPTS,
				denial -> trail.denied(AccountActivity.of(Kind.PASSWORD_CHANGE_LIMITED, userId), denial));
		User found = users.findById(userId).filter(User::isActive).orElseThrow(() -> loggedOut(userId));
		// 확인에 쓴 해시. 저장할 때 이 값이 그대로인지 다시 본다.
		String verifiedHash = found.getPasswordHash();
		if (currentPassword == null || !passwordEncoder.matches(currentPassword, verifiedHash)) {
			throw recordedMismatch(userId);
		}
		// 지금 비밀번호가 맞았다. 대입이 아니므로 센 것을 되돌린다. 아래에서 거부되더라도(규칙에 안 맞는 새 비밀번호) 세지 않는다.
		refund(permit);
		requireAcceptable(newPassword, found.getLoginId());
		if (passwordEncoder.matches(newPassword, verifiedHash)) {
			// 바꿀 것은 없다. 다만 앞선 요청이 비밀번호를 바꾸고 다른 세션을 끊다 실패했을 수 있다. 그 요청을 다시 보내는 길이 이것이다.
			afterOwnPasswordChange(() -> userSessions.terminateOthers(userId, currentSessionId));
			throw new BusinessException(AuthErrorCode.PASSWORD_UNCHANGED);
		}
		String passwordHash = passwordEncoder.encode(newPassword);
		long authVersion;
		try {
			authVersion = transaction.execute(status -> {
				User user = users.findByIdForUpdate(userId)
					.filter(User::isActive)
					.orElseThrow(() -> new BusinessException(CommonErrorCode.UNAUTHENTICATED));
				// 위에서 확인한 뒤에 비밀번호가 바뀌었을 수 있다(관리자가 다시 정했거나, 다른 세션에서 본인이 바꿨다).
				// 그러면 방금 확인한 "지금 비밀번호"는 더는 지금 비밀번호가 아니다. 옛 비밀번호만 아는 요청이 새 비밀번호를 덮어쓰지 못하게 한다.
				if (!user.getPasswordHash().equals(verifiedHash)) {
					throw new BusinessException(AuthErrorCode.CURRENT_PASSWORD_MISMATCH);
				}
				user.changePassword(passwordHash);
				trail.done(AccountActivity.of(Kind.PASSWORD_CHANGED, userId));
				return user.getAuthVersion();
			});
		}
		catch (BusinessException ex) {
			// 트랜잭션은 끝났다. 거부를 적고 뒤처리를 하는 것은 여기서 한다.
			if (ex.getErrorCode() == AuthErrorCode.CURRENT_PASSWORD_MISMATCH) {
				throw recordedMismatch(userId);
			}
			if (ex.getErrorCode() == CommonErrorCode.UNAUTHENTICATED) {
				throw loggedOut(userId);
			}
			throw ex;
		}
		// 다른 세션을 끊기 전에 넣는다. 끊다가 실패해도 지금 세션은 새 번호를 갖고 있어야 한다.
		currentSession.setAttribute(LoginService.AUTH_VERSION_ATTRIBUTE, authVersion);
		afterOwnPasswordChange(() -> userSessions.terminateOthers(userId, currentSessionId));
	}

	// 지금 비밀번호가 틀렸다(또는 확인한 뒤에 바뀌어 더는 맞지 않는다). 트랜잭션 밖에서 부른다.
	private BusinessException recordedMismatch(long userId) {
		BusinessException mismatch = new BusinessException(AuthErrorCode.CURRENT_PASSWORD_MISMATCH);
		trail.denied(AccountActivity.of(Kind.PASSWORD_CHANGE_MISMATCH, userId), mismatch);
		return mismatch;
	}

	// 되돌리지 못해도 변경은 계속한다. 횟수가 더 남을 뿐이라 안전한 쪽이다(세지 못하면 막는 것은 셀 때의 규칙이다).
	private void refund(Permit permit) {
		try {
			limiter.refund(permit);
		}
		catch (RuntimeException ex) {
			log.error("비밀번호 변경 횟수를 되돌리지 못했다. 맞은 요청이 한 번으로 세어진 채 남는다: userId={}",
					permit.account().accountId(), ex);
		}
	}

	// 계정을 바꾸고 커밋한 뒤의 세션 끊기. 실패해도 계정은 이미 바뀌어 있으므로 일반 오류로 내보내지 않는다.
	private static void afterCommit(Runnable terminateSessions) {
		try {
			terminateSessions.run();
		}
		catch (RuntimeException ex) {
			throw new BusinessException(AuthErrorCode.SESSION_TERMINATION_FAILED, null, ex);
		}
	}

	// 본인 비밀번호 변경은 같은 요청을 다시 보내는 것으로는 복구되지 않는다(지금 비밀번호가 이미 바뀌었다). 무엇을 하면 되는지를 문구로 알린다.
	private static void afterOwnPasswordChange(Runnable terminateOtherSessions) {
		try {
			terminateOtherSessions.run();
		}
		catch (RuntimeException ex) {
			throw new BusinessException(AuthErrorCode.SESSION_TERMINATION_FAILED,
					"비밀번호는 바뀌었지만 다른 기기를 로그아웃시키지 못했습니다. 새 비밀번호로 비밀번호 변경을 한 번 더 해 주세요.", ex);
		}
	}

	// 활성 관리자를 잠그고 읽는다. 관리자 수를 줄일 수 있는 변경의 첫 조회다(잠그는 순서가 같아야 서로 물려 멈추지 않는다).
	private List<User> lockActiveAdmins() {
		return users.findAllByRoleAndStatusForUpdate(UserRole.ADMIN, UserStatus.ACTIVE);
	}

	// 그 계정이 활성 관리자라면, 그 계정 말고도 활성 관리자가 있어야 한다. 없으면 아무도 계정을 관리할 수 없게 된다.
	// 화면으로는 여기에 걸리기 어렵다(일하는 관리자 자신이 한 명이다). 두 관리자가 서로를 동시에 차단하는 경우를 막는다.
	private static void requireAnotherActiveAdmin(List<User> activeAdmins, User user) {
		boolean activeAdmin = user.getRole() == UserRole.ADMIN && user.isActive();
		if (!activeAdmin) {
			return;
		}
		boolean another = activeAdmins.stream().anyMatch(admin -> !admin.getId().equals(user.getId()));
		if (!another) {
			throw new BusinessException(AuthErrorCode.LAST_ADMIN);
		}
	}

	private boolean isSelf(long userId) {
		return currentActor.get() instanceof Actor.User self && self.accountId() == userId;
	}

	private static void requireManageable(UserRole role) {
		if (role.belongsToPartner()) {
			throw new BusinessException(AuthErrorCode.PARTNER_ACCOUNT_NOT_SUPPORTED);
		}
	}

	private static void requireMatching(UserRole role, Long campusId) {
		if (role.belongsToCampus() != (campusId != null)) {
			throw new BusinessException(AuthErrorCode.ROLE_CAMPUS_MISMATCH);
		}
	}

	// 계정의 지점에 걸린 외래 키에 걸렸으면 없는 지점이다. 다른 제약에 걸린 것은 그대로 던진다(서버 오류로 드러나야 한다).
	// 이름을 가리는 방식은 유니크 키와 같다(MySQL은 외래 키 위반에도 제약 이름을 준다).
	private static RuntimeException campusNotFoundOr(DataIntegrityViolationException ex) {
		return UniqueKeys.isViolationOf(ex, CAMPUS_FOREIGN_KEY)
				? new BusinessException(AuthErrorCode.CAMPUS_NOT_FOUND, null, ex) : ex;
	}

	// 무엇을 지켜야 하는지는 응답의 문구가 알려 준다(어느 것을 어겼는지는 가리지 않는다). 비밀번호는 어디에도 싣지 않는다.
	private static void requireAcceptable(String password, String loginId) {
		if (PasswordPolicy.check(password, loginId).isPresent()) {
			throw new BusinessException(AuthErrorCode.PASSWORD_NOT_ACCEPTABLE);
		}
	}

	private static void requireNoTransaction(String method) {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("AccountService#" + method + "은(는) 트랜잭션 밖에서 호출해야 한다");
		}
	}

	// 거부된 시도 가운데 남겨야 하는 것을, 그 일의 트랜잭션이 끝난 자리에서 적는다.
	private <T> T recordingDenial(Long userId, Supplier<T> work) {
		try {
			return work.get();
		}
		catch (BusinessException ex) {
			ErrorCode code = ex.getErrorCode();
			if (code == AuthErrorCode.LOGIN_ID_DUPLICATED) {
				trail.denied(AccountActivity.loginIdDuplicated(), ex);
			}
			else if (userId != null && code == AuthErrorCode.SELF_CHANGE_NOT_ALLOWED) {
				trail.denied(AccountActivity.of(Kind.SELF_CHANGE_DENIED, userId), ex);
			}
			else if (userId != null && code == AuthErrorCode.LAST_ADMIN) {
				trail.denied(AccountActivity.of(Kind.LAST_ADMIN_DENIED, userId), ex);
			}
			throw ex;
		}
	}

	private static BusinessException notFound() {
		return new BusinessException(CommonErrorCode.NOT_FOUND);
	}

	// 차단됐거나 사라진 계정인데 그 계정의 세션으로 요청이 왔다. 차단할 때 세션을 끊지 못했거나, 끊기 직전에 들어온 요청이다.
	// 남은 세션을 여기서 끊고 로그인하지 않은 것으로 답한다. 트랜잭션 밖에서 부른다.
	private BusinessException loggedOut(long userId) {
		log.warn("차단됐거나 없는 계정의 세션으로 요청이 왔다. 그 계정의 세션을 끊는다: userId={}", userId);
		try {
			userSessions.terminateAll(userId);
		}
		catch (RuntimeException ex) {
			// 왜 못 끊었는지는 UserSessions가 남겼다. 이 요청은 어차피 거부한다.
			log.warn("차단됐거나 없는 계정의 세션을 끊지 못했다: userId={}", userId);
		}
		return new BusinessException(CommonErrorCode.UNAUTHENTICATED);
	}

	private record Updated(AccountView account, boolean affiliationChanged) {
	}

}
