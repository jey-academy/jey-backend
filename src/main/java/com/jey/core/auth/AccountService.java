package com.jey.core.auth;

import com.jey.core.auth.domain.User;
import com.jey.core.auth.domain.UserRepository;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.CommonErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 계정의 상태를 바꾼다. 계정을 만들고 고치는 기능은 계정 관리 작업에서 추가한다.
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
	 *
	 * @throws BusinessException 계정이 없을 때
	 */
	public void disable(long userId) {
		transaction.executeWithoutResult(status -> {
			User user = users.findById(userId).orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));
			user.disable();
		});
		// 차단이 DB에 반영된 뒤에 세션을 끊는다. 순서가 반대면, 세션을 끊은 직후 반영 전에 다시 로그인한 세션이 살아남는다.
		userSessions.terminateAll(userId);
	}

}
