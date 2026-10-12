package com.jey.core.auth;

import com.jey.core.auth.PasswordPolicy.Violation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordPolicyTest {

	@Test
	void 열_자부터_받는다() {
		assertThat(PasswordPolicy.checkLength("123456789")).contains(Violation.TOO_SHORT);
		assertThat(PasswordPolicy.checkLength("1234567890")).isEmpty();
	}

	@Test
	void 비었거나_공백뿐이면_받지_않는다() {
		assertThat(PasswordPolicy.checkLength(null)).contains(Violation.TOO_SHORT);
		assertThat(PasswordPolicy.checkLength("")).contains(Violation.TOO_SHORT);
		assertThat(PasswordPolicy.checkLength("            ")).contains(Violation.TOO_SHORT);
	}

	// BCrypt는 72바이트까지만 읽는다. 넘는 부분이 조용히 버려지면 뒤쪽이 다른 비밀번호로도 로그인된다.
	@Test
	void 일흔두_바이트까지_받는다() {
		assertThat(PasswordPolicy.checkLength("a".repeat(72))).isEmpty();
		assertThat(PasswordPolicy.checkLength("a".repeat(73))).contains(Violation.TOO_LONG);
	}

	// 한글은 한 글자가 3바이트다. 글자 수로는 짧아 보여도 넘을 수 있다.
	@Test
	void 길이는_글자_수가_아니라_바이트로_잰다() {
		assertThat(PasswordPolicy.checkLength("가".repeat(24))).isEmpty();
		assertThat(PasswordPolicy.checkLength("가".repeat(25))).contains(Violation.TOO_LONG);
	}

	@Test
	void 글자_종류는_가리지_않는다() {
		assertThat(PasswordPolicy.checkLength("aaaaaaaaaa")).isEmpty();
		assertThat(PasswordPolicy.checkLength("correct horse battery")).isEmpty();
	}

	@Test
	void 아이디와_같은_비밀번호는_받지_않는다() {
		assertThat(PasswordPolicy.check("desk.dongtan", "desk.dongtan")).contains(Violation.SAME_AS_LOGIN_ID);
		assertThat(PasswordPolicy.check("Desk.Dongtan", "desk.dongtan")).contains(Violation.SAME_AS_LOGIN_ID);
		assertThat(PasswordPolicy.check(" desk.dongtan ", "desk.dongtan")).contains(Violation.SAME_AS_LOGIN_ID);
		assertThat(PasswordPolicy.check("desk.dongtan1", "desk.dongtan")).isEmpty();
	}

	@Test
	void 길이를_어기면_길이를_먼저_알린다() {
		assertThat(PasswordPolicy.check("desk", "desk")).contains(Violation.TOO_SHORT);
		assertThat(PasswordPolicy.check("long-enough-password", null)).isEmpty();
	}

}
