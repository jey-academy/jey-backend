package com.jey.core.auth;

import com.jey.core.auth.domain.User;
import com.jey.core.auth.domain.UserRepository;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.CommonErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 계정의 상태를 바꾼다.
 */
@Service
public class AccountService {

	private final UserRepository users;

	private final UserSessions userSessions;

	private final TransactionTemplate transaction;

	AccountService(UserRepository users, UserSessions userSessions, TransactionTemplate transaction) {
		this.users = users;
		this.userSessions = userSessions;
		this.transaction = transaction;
	}

	/**
	 * 계정을 차단한다. 다음 로그인을 막고, 이미 로그인된 세션도 모두 끊는다.
	 * 이미 처리 중이던 요청은 끝까지 처리된다. 세션을 끊는 데 실패하면 예외가 나며, 다시 호출해도 안전하다.
	 *
	 * @throws BusinessException 계정이 없을 때
	 * @throws IllegalStateException 다른 트랜잭션 안에서 호출했을 때
	 */
	public void disable(long userId) {
		// 바깥 트랜잭션에 합류하면 차단이 DB에 반영되기 전에 세션을 끊게 되어 아래 순서가 깨진다.
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("AccountService#disable은 트랜잭션 밖에서 호출해야 한다");
		}
		transaction.executeWithoutResult(status -> {
			User user = users.findById(userId).orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
			user.disable();
		});
		// 차단이 DB에 반영된 뒤에 세션을 끊는다. 순서가 반대면, 세션을 끊은 직후 반영 전에 다시 로그인한 세션이 살아남는다.
		// 이 순서로도 틈이 완전히 닫히지는 않는다. 차단 직전에 로그인을 시작한 요청이 세션을 늦게 저장하면 살아남을 수 있어서,
		// LoginService가 세션을 만들기 직전에 계정 상태를 다시 읽어 그 틈을 수 밀리초로 줄인다(ADR-0019).
		userSessions.terminateAll(userId);
	}

}
