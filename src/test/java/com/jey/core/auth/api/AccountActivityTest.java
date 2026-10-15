package com.jey.core.auth.api;

import com.jey.core.auth.api.AccountActivity.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

// 이 값은 감사 로그의 한 줄이 된다. 종류에 맞지 않는 조합이 그럴듯한 기록으로 남지 않게 만들 때 막는다.
class AccountActivityTest {

	@Test
	void 만든_계정은_역할과_지점을_싣는다() {
		AccountActivity created = AccountActivity.created(7, UserRole.STAFF, 1L, null);

		assertThat(created.kind()).isEqualTo(Kind.CREATED);
		assertThat(created.accountId()).isEqualTo(7L);
		assertThat(created.role()).isEqualTo(UserRole.STAFF);
		assertThat(created.campusId()).isEqualTo(1L);
		assertThat(created.partnerId()).isNull();
		assertThat(AccountActivity.created(8, UserRole.ADMIN, null, null).campusId()).isNull();
	}

	// 연계 학원 계정은 어느 학원의 것인지가 만든 기록에 남아야 한다. 계정을 만드는 곳(패스 모듈)이 따로 적지 않는다.
	@Test
	void 만든_연계_학원_계정은_연계_학원_식별자를_싣는다() {
		AccountActivity created = AccountActivity.created(7, UserRole.PARTNER, null, 3L);

		assertThat(created.role()).isEqualTo(UserRole.PARTNER);
		assertThat(created.partnerId()).isEqualTo(3L);
		assertThat(created.campusId()).isNull();
	}

	@Test
	void 연계_학원_식별자는_연계_학원_계정을_만든_기록에만_있다() {
		assertThatIllegalArgumentException().isThrownBy(() -> AccountActivity.created(7, UserRole.PARTNER, null, null));
		assertThatIllegalArgumentException().isThrownBy(() -> AccountActivity.created(7, UserRole.STAFF, 1L, 3L));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new AccountActivity(Kind.UPDATED, 7L, UserRole.PARTNER, null, 3L, true, false));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new AccountActivity(Kind.DISABLED, 7L, null, null, 3L, false, false));
	}

	@Test
	void 고친_계정은_고친_뒤의_역할과_지점과_무엇을_고쳤는지를_싣는다() {
		AccountActivity moved = AccountActivity.updated(7, UserRole.STAFF, 2L, false, true);

		assertThat(moved.kind()).isEqualTo(Kind.UPDATED);
		assertThat(moved.role()).isEqualTo(UserRole.STAFF);
		assertThat(moved.campusId()).isEqualTo(2L);
		assertThat(moved.nameChanged()).isFalse();
		assertThat(moved.affiliationChanged()).isTrue();
	}

	// 무엇을 고쳤는지 없는 "고쳤다"는 기록이 아니다. 바뀐 것이 없으면 적지 않는다.
	@Test
	void 아무것도_고치지_않은_고치기는_만들_수_없다() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> AccountActivity.updated(7, UserRole.STAFF, 1L, false, false));
	}

	@Test
	void 아이디_중복은_대상_계정이_없다() {
		assertThat(AccountActivity.loginIdDuplicated().accountId()).isNull();
		assertThatIllegalArgumentException().isThrownBy(() -> AccountActivity.of(Kind.LOGIN_ID_DUPLICATED, 7));
	}

	@ParameterizedTest
	@EnumSource(value = Kind.class, names = { "CREATED", "UPDATED", "LOGIN_ID_DUPLICATED" }, mode = EnumSource.Mode.EXCLUDE)
	void 덧붙일_값이_없는_종류는_계정만_가리킨다(Kind kind) {
		AccountActivity activity = AccountActivity.of(kind, 7);

		assertThat(activity.accountId()).isEqualTo(7L);
		assertThat(activity.role()).isNull();
		assertThat(activity.campusId()).isNull();
		assertThat(activity.partnerId()).isNull();
	}

	// 역할이 있어야 하는 종류를 계정만으로 만들면 역할 없는 "만들었다"가 된다.
	@ParameterizedTest
	@EnumSource(value = Kind.class, names = { "CREATED", "UPDATED" })
	void 역할이_있어야_하는_종류는_계정만으로_만들_수_없다(Kind kind) {
		assertThatIllegalArgumentException().isThrownBy(() -> AccountActivity.of(kind, 7));
	}

	@Test
	void 종류에_맞지_않는_값은_실을_수_없다() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new AccountActivity(Kind.DISABLED, 7L, UserRole.STAFF, null, null, false, false));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new AccountActivity(Kind.DISABLED, 7L, null, 1L, null, false, false));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new AccountActivity(Kind.DISABLED, 7L, null, null, null, true, false));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new AccountActivity(Kind.DISABLED, null, null, null, null, false, false));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new AccountActivity(Kind.CREATED, 7L, UserRole.STAFF, 1L, null, true, false));
	}

	@Test
	void 한_일과_거부된_시도를_가른다() {
		assertThat(Kind.values()).filteredOn(Kind::denied)
			.containsExactly(Kind.LOGIN_ID_DUPLICATED, Kind.SELF_CHANGE_DENIED, Kind.LAST_ADMIN_DENIED,
					Kind.PASSWORD_CHANGE_MISMATCH, Kind.PASSWORD_CHANGE_LIMITED);
	}

}
