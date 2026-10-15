package com.jey.core.account;

import com.jey.core.audit.api.AuditEntry;
import com.jey.core.audit.api.AuditLog;
import com.jey.core.audit.api.AuditOutcome;
import com.jey.core.auth.api.AccountActivity;
import com.jey.core.auth.api.AccountActivity.Kind;
import com.jey.core.auth.api.AuthRefs;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.CommonErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

// 실제 감사 로그에 줄이 남는지는 AccountManagementTest가 본다. 여기서는 종류를 어느 행위로, 무엇을 실어 적는지를 본다.
class AuditLogAccountTrailTest {

	private final AuditLog auditLog = mock(AuditLog.class);

	private final AuditLogAccountTrail trail = new AuditLogAccountTrail(auditLog);

	// 한 일인지 거부된 시도인지는 종류가 정한다. 행위에 따로 적지 않으므로 어긋날 수 없지만, 대응이 뒤섞이지 않았는지 본다.
	@ParameterizedTest
	@EnumSource(Kind.class)
	void 종류마다_같은_이름의_행위로_적고_결과는_종류를_따른다(Kind kind) {
		AccountAuditAction action = AccountAuditAction.of(kind);

		assertThat(action.name()).isEqualTo(kind.name());
		assertThat(action.outcome()).isEqualTo(kind.denied() ? AuditOutcome.DENIED : AuditOutcome.DONE);
		assertThat(action.code()).startsWith("account.");
	}

	@Test
	void 만든_계정은_대상과_지점과_역할을_적는다() {
		trail.done(AccountActivity.created(7, UserRole.STAFF, 2L, null));

		AuditEntry entry = recorded();
		assertThat(entry.action()).isEqualTo(AccountAuditAction.CREATED);
		assertThat(entry.target()).isEqualTo(AuthRefs.account(7));
		assertThat(entry.campusId()).isEqualTo(2L);
		assertThat(entry.detail()).containsOnlyKeys("role").containsEntry("role", "STAFF");
	}

	@Test
	void 만든_연계_학원_계정은_연계_학원_식별자를_적고_지점은_적지_않는다() {
		trail.done(AccountActivity.created(7, UserRole.PARTNER, null, 3L));

		AuditEntry entry = recorded();
		assertThat(entry.action()).isEqualTo(AccountAuditAction.CREATED);
		assertThat(entry.campusId()).isNull();
		assertThat(entry.detail()).containsOnlyKeys("role", "partnerId")
			.containsEntry("role", "PARTNER")
			.containsEntry("partnerId", 3L);
	}

	// 지점을 옮긴 기록에 어느 지점으로 갔는지가 있어야 권한이 어떻게 바뀌었는지 뒤에서 볼 수 있다.
	@Test
	void 고친_계정은_고친_뒤의_지점과_무엇을_고쳤는지를_적는다() {
		trail.done(AccountActivity.updated(7, UserRole.STAFF, 2L, false, true));

		AuditEntry entry = recorded();
		assertThat(entry.action()).isEqualTo(AccountAuditAction.UPDATED);
		assertThat(entry.campusId()).isEqualTo(2L);
		assertThat(entry.detail()).containsOnlyKeys("role", "nameChanged", "affiliationChanged")
			.containsEntry("nameChanged", false)
			.containsEntry("affiliationChanged", true);
	}

	@Test
	void 덧붙일_값이_없는_일은_대상만_적는다() {
		trail.done(AccountActivity.of(Kind.PASSWORD_RESET, 7));

		AuditEntry entry = recorded();
		assertThat(entry.target()).isEqualTo(AuthRefs.account(7));
		assertThat(entry.campusId()).isNull();
		assertThat(entry.detail()).isEmpty();
	}

	@Test
	void 아이디_중복은_대상_없이_적는다() {
		trail.denied(AccountActivity.loginIdDuplicated(), new BusinessException(CommonErrorCode.CONFLICT));

		AuditEntry entry = recorded();
		assertThat(entry.action()).isEqualTo(AccountAuditAction.LOGIN_ID_DUPLICATED);
		assertThat(entry.target()).isNull();
	}

	// 한 일은 그 일의 트랜잭션 안에서, 거부된 시도는 끝난 뒤에 적는다. 잘못된 쪽으로 부르면 적지 않고 알린다.
	@Test
	void 한_일과_거부된_시도를_바꿔_부를_수_없다() {
		BusinessException denial = new BusinessException(CommonErrorCode.CONFLICT);

		assertThatIllegalArgumentException().isThrownBy(() -> trail.done(AccountActivity.of(Kind.LAST_ADMIN_DENIED, 7)));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> trail.denied(AccountActivity.of(Kind.DISABLED, 7), denial));

		verifyNoInteractions(auditLog);
	}

	// 기록 없이 "거부됐다"는 응답을 내보내지 않는다. 원래의 거부는 그 실패에 붙어 로그에 같이 남는다.
	@Test
	void 거부를_적지_못하면_그_실패를_던지고_원래의_거부를_붙인다() {
		BusinessException denial = new BusinessException(CommonErrorCode.CONFLICT);
		IllegalStateException failure = new IllegalStateException("감사 로그 저장 실패");
		doThrow(failure).when(auditLog).record(any());

		assertThatThrownBy(() -> trail.denied(AccountActivity.of(Kind.SELF_CHANGE_DENIED, 7), denial)).isSameAs(failure)
			.satisfies(thrown -> assertThat(thrown.getSuppressed()).containsExactly(denial));
	}

	private AuditEntry recorded() {
		ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditLog).record(entry.capture());
		return entry.getValue();
	}

}
