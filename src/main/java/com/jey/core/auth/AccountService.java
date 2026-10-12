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
 * <p>계정을 바꾸는 메서드는 모두 같은 순서로 일한다: 트랜잭션에서 고치고, 커밋한 뒤, 그 계정의 세션을 끊는다.
 * 순서가 반대면, 세션을 끊은 직후 변경이 반영되기 전에 다시 로그인한 세션이 옛 권한으로 살아남는다.
 * 그래서 바깥 트랜잭션 안에서는 부를 수 없다(합류하면 커밋되기 전에 세션을 끊게 된다).
 * 거부된 시도를 감사 로그에 적는 것도 트랜잭션이 끝난 뒤여야 한다.
 *
 * <p>감사 로그와 지점 목록은 직접 부르지 않는다(둘 다 이 패키지를 쓰고 있어 서로 물린다).
 * 기록은 {@link AccountAuditTrail}에 맡기고, 없는 지점은 DB의 외래 키가 걸러낸 것을 받아 알린다.
 *
 * <p>비밀번호 원문은 해시하는 데만 쓰고 로그, 예외 문구, 감사 로그에 넣지 않는다.
 */
@Service
@EnableConfigurationProperties(PasswordChangeLimitProperties.class)
public class AccountService {

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
				if (users.findByLoginId(loginId).isPresent()) {
					throw new BusinessException(AuthErrorCode.LOGIN_ID_DUPLICATED);
				}
				throw campusNotFoundOr(ex, account.campusId());
			}
		});
	}

	/**
	 * 이름, 역할, 소속 지점을 고친다. 역할이나 지점이 바뀌면 그 계정의 세션을 모두 끊는다(세션에 옛 역할과 지점이 들어 있다).
	 * 이름만 바뀌면 끊지 않는다. 세션에 든 이름은 다음 로그인 때 바뀐다.
	 *
	 * <p>세션을 끊는 데 실패하면 예외가 난다. 그때 변경은 이미 반영돼 있어서, 같은 내용으로 다시 고쳐도 "바뀐 것이 없다"로 끝나고 세션은 끊기지 않는다.
	 * 남은 세션은 {@link #disable}이나 {@link #resetPassword}로 끊는다(둘은 부를 때마다 끊는다).
	 *
	 * @throws BusinessException 계정이 없거나, 연계 학원 역할과 얽힌 변경이거나, 역할과 지점의 조합이 맞지 않거나, 없는 지점이거나,
	 * 자기 자신의 역할·지점을 바꾸려 하거나, 마지막 남은 활성 관리자를 다른 역할로 바꾸려 할 때
	 * @throws IllegalStateException 다른 트랜잭션 안에서 호출했을 때
	 */
	public AccountView update(long userId, AccountChange change) {
		requireNoTransaction("update");
		Updated updated = recordingDenial(userId, () -> {
			try {
				return updateInTransaction(userId, change);
			}
			catch (DataIntegrityViolationException ex) {
				throw campusNotFoundOr(ex, change.campusId());
			}
		});
		if (updated.affiliationChanged()) {
			userSessions.terminateAll(userId);
		}
		return updated.account();
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
				trail.done(AccountActivity.updated(userId, user.getRole(), nameChanged, affiliationChanged));
			}
			return new Updated(AccountView.from(user), affiliationChanged);
		});
	}

	/**
	 * 계정을 차단한다. 다음 로그인을 막고, 이미 로그인된 세션도 모두 끊는다.
	 * 이미 처리 중이던 요청은 끝까지 처리된다. 세션을 끊는 데 실패하면 예외가 나며, 다시 호출해도 안전하다(이미 차단된 계정이어도 세션을 다시 끊는다).
	 *
	 * @throws BusinessException 계정이 없거나, 자기 자신이거나, 마지막 남은 활성 관리자일 때
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
		userSessions.terminateAll(userId);
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
	 * @throws BusinessException 계정이 없거나, 자기 자신이거나, 비밀번호가 규칙에 맞지 않을 때
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
		userSessions.terminateAll(userId);
	}

	/**
	 * 로그인한 사람이 자기 비밀번호를 바꾼다. 지금 세션은 남기고 그 계정의 다른 세션을 모두 끊는다.
	 *
	 * <p>지금 비밀번호를 틀린 횟수를 계정별로 센다. 한도를 넘으면 맞는 비밀번호로도 구간이 끝날 때까지 바꿀 수 없다.
	 * 로그인된 세션을 손에 넣은 사람이 지금 비밀번호를 대입으로 알아내는 것을 느리게 한다. 지금 비밀번호가 맞은 요청은 세지 않는다.
	 *
	 * @param currentSessionId 이 요청의 세션 ID. 이 세션은 끊지 않는다
	 * @throws com.jey.core.shared.RateLimitedException 틀린 횟수가 한도를 넘었을 때
	 * @throws BusinessException 지금 비밀번호가 틀리거나, 새 비밀번호가 규칙에 맞지 않거나, 지금 비밀번호와 같을 때
	 * @throws IllegalStateException 로그인한 계정을 알 수 없거나, 다른 트랜잭션 안에서 호출했을 때
	 */
	public void changeOwnPassword(String currentPassword, String newPassword, String currentSessionId) {
		requireNoTransaction("changeOwnPassword");
		if (!(currentActor.get() instanceof Actor.User self)) {
			throw new IllegalStateException("로그인한 계정 없이 비밀번호를 바꿀 수 없다");
		}
		long userId = self.accountId();
		// 확인하기 전에 센다. 확인한 뒤에 세면 동시에 보낸 요청이 모두 한도 확인을 통과한다.
		Permit permit = limiter.acquire(passwordChangeLimit, self, AuthErrorCode.TOO_MANY_PASSWORD_ATTEMPTS,
				denial -> trail.denied(AccountActivity.of(Kind.PASSWORD_CHANGE_LIMITED, userId), denial));
		User found = users.findById(userId).orElseThrow(AccountService::notFound);
		if (currentPassword == null || !passwordEncoder.matches(currentPassword, found.getPasswordHash())) {
			BusinessException mismatch = new BusinessException(AuthErrorCode.CURRENT_PASSWORD_MISMATCH);
			trail.denied(AccountActivity.of(Kind.PASSWORD_CHANGE_MISMATCH, userId), mismatch);
			throw mismatch;
		}
		// 지금 비밀번호가 맞았다. 대입이 아니므로 센 것을 되돌린다. 아래에서 거부되더라도(규칙에 안 맞는 새 비밀번호) 세지 않는다.
		limiter.refund(permit);
		requireAcceptable(newPassword, found.getLoginId());
		if (passwordEncoder.matches(newPassword, found.getPasswordHash())) {
			throw new BusinessException(AuthErrorCode.PASSWORD_UNCHANGED);
		}
		String passwordHash = passwordEncoder.encode(newPassword);
		transaction.executeWithoutResult(status -> {
			User user = users.findByIdForUpdate(userId).orElseThrow(AccountService::notFound);
			user.changePassword(passwordHash);
			trail.done(AccountActivity.of(Kind.PASSWORD_CHANGED, userId));
		});
		userSessions.terminateOthers(userId, currentSessionId);
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

	// 지점이 실린 저장이 DB 제약에 걸렸으면 없는 지점이다(계정의 지점에는 외래 키가 걸려 있다). 지점이 없었다면 다른 문제다.
	private static RuntimeException campusNotFoundOr(DataIntegrityViolationException ex, Long campusId) {
		return (campusId != null) ? new BusinessException(AuthErrorCode.CAMPUS_NOT_FOUND) : ex;
	}

	// 무엇을 어겼는지는 응답의 문구가 알려 준다. 비밀번호는 어디에도 싣지 않는다.
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
			else if (code == AuthErrorCode.SELF_CHANGE_NOT_ALLOWED) {
				trail.denied(AccountActivity.of(Kind.SELF_CHANGE_DENIED, userId), ex);
			}
			else if (code == AuthErrorCode.LAST_ADMIN) {
				trail.denied(AccountActivity.of(Kind.LAST_ADMIN_DENIED, userId), ex);
			}
			throw ex;
		}
	}

	private static BusinessException notFound() {
		return new BusinessException(CommonErrorCode.NOT_FOUND);
	}

	private record Updated(AccountView account, boolean affiliationChanged) {
	}

}
