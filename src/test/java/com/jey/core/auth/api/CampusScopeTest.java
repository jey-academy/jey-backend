package com.jey.core.auth.api;

import com.jey.core.shared.BusinessException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CampusScopeTest {

	private static final AuthenticatedUser ADMIN = new AuthenticatedUser(1L, "admin", "박원장", UserRole.ADMIN, null,
			null);

	private static final AuthenticatedUser STAFF_OF_1 = new AuthenticatedUser(2L, "desk", "김직원", UserRole.STAFF, 1L,
			null);

	private static final AuthenticatedUser PARTNER = new AuthenticatedUser(3L, "partner", "연계학원", UserRole.PARTNER,
			null, 7L);

	@Test
	void 관리자는_전_지점이다() {
		assertThat(ADMIN.campusScope()).isEqualTo(new CampusScope.All());
		assertThat(ADMIN.campusScopeFor(null)).isEqualTo(new CampusScope.All());
	}

	@Test
	void 관리자가_지점을_고르면_그_지점이다() {
		assertThat(ADMIN.campusScopeFor(2L)).isEqualTo(new CampusScope.Only(2L));
	}

	// "지점을 안 골랐다"를 전 지점으로 해석하면 직원이 다른 지점 수납을 보게 된다.
	@Test
	void 직원은_지점을_고르지_않아도_소속_지점이다() {
		assertThat(STAFF_OF_1.campusScope()).isEqualTo(new CampusScope.Only(1L));
		assertThat(STAFF_OF_1.campusScopeFor(null)).isEqualTo(new CampusScope.Only(1L));
		assertThat(STAFF_OF_1.campusScopeFor(1L)).isEqualTo(new CampusScope.Only(1L));
	}

	// 조용히 소속 지점으로 바꿔 답하면 화면에는 고른 지점의 자료처럼 보인다.
	@Test
	void 직원이_다른_지점을_고르면_거부한다() {
		assertCampusNotAllowed(() -> STAFF_OF_1.campusScopeFor(2L));
	}

	@Test
	void 직원은_소속_지점의_자료만_다룬다() {
		assertThatCode(() -> STAFF_OF_1.requireCampus(1L)).doesNotThrowAnyException();
		assertCampusNotAllowed(() -> STAFF_OF_1.requireCampus(2L));
	}

	@Test
	void 관리자는_어느_지점의_자료든_다룬다() {
		assertThatCode(() -> ADMIN.requireCampus(1L)).doesNotThrowAnyException();
		assertThatCode(() -> ADMIN.requireCampus(2L)).doesNotThrowAnyException();
	}

	// 역할 표시를 잘못 붙여 연계 학원이 여기까지 오더라도 지점 자료는 열리지 않는다.
	@Test
	void 연계_학원은_지점_자료를_다루지_못한다() {
		assertCampusNotAllowed(PARTNER::campusScope);
		assertCampusNotAllowed(() -> PARTNER.campusScopeFor(null));
		assertCampusNotAllowed(() -> PARTNER.campusScopeFor(1L));
		assertCampusNotAllowed(() -> PARTNER.requireCampus(1L));
	}

	@Test
	void 범위가_지점을_포함하는지() {
		assertThat(new CampusScope.All().includes(99L)).isTrue();
		assertThat(new CampusScope.Only(1L).includes(1L)).isTrue();
		assertThat(new CampusScope.Only(1L).includes(2L)).isFalse();
	}

	private static void assertCampusNotAllowed(ThrowingCallable call) {
		assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
				ex -> assertThat(ex.getErrorCode()).isEqualTo(AccessErrorCode.CAMPUS_NOT_ALLOWED));
	}

}
