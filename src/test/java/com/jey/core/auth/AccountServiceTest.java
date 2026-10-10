package com.jey.core.auth;

import java.util.Optional;

import com.jey.core.auth.api.UserRole;
import com.jey.core.auth.domain.User;
import com.jey.core.auth.domain.UserRepository;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.CommonErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// 계정을 차단하면 그 계정의 세션이 실제로 끊기는지는 AuthLoginTest가 확인한다. 여기서는 순서와 실패하는 경우를 본다.
class AccountServiceTest {

	private static final long USER_ID = 7L;

	private final UserRepository users = mock(UserRepository.class);

	private final UserSessions userSessions = mock(UserSessions.class);

	private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

	private final AccountService accountService = new AccountService(users, userSessions,
			new TransactionTemplate(transactionManager));

	@AfterEach
	void clearTransactionState() {
		TransactionSynchronizationManager.setActualTransactionActive(false);
	}

	// 순서가 반대면, 세션을 끊은 직후 차단이 반영되기 전에 다시 로그인한 세션이 살아남는다.
	@Test
	void 차단을_DB에_반영한_뒤에_세션을_끊는다() {
		when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		User user = User.create("desk", "{noop}not-a-real-hash", "김직원", UserRole.STAFF, 1L);
		when(users.findById(USER_ID)).thenReturn(Optional.of(user));

		accountService.disable(USER_ID);

		assertThat(user.isActive()).isFalse();
		InOrder order = inOrder(transactionManager, userSessions);
		order.verify(transactionManager).commit(any());
		order.verify(userSessions).terminateAll(USER_ID);
	}

	@Test
	void 없는_계정이면_NOT_FOUND이고_세션은_건드리지_않는다() {
		when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		when(users.findById(USER_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> accountService.disable(USER_ID))
				.isInstanceOfSatisfying(BusinessException.class,
						ex -> assertThat(ex.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND));
		verify(transactionManager).rollback(any());
		verifyNoInteractions(userSessions);
	}

	// 바깥 트랜잭션에 합류하면 차단이 DB에 반영되기 전에 세션을 끊게 된다. 그런 호출은 처음부터 막는다.
	@Test
	void 다른_트랜잭션_안에서는_호출할_수_없다() {
		TransactionSynchronizationManager.setActualTransactionActive(true);

		assertThatIllegalStateException().isThrownBy(() -> accountService.disable(USER_ID));
		verifyNoInteractions(users, userSessions, transactionManager);
	}

}
