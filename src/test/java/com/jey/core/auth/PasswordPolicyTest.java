package com.jey.core.auth;

import com.jey.core.auth.PasswordPolicy.Violation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordPolicyTest {

	@Test
	void 열_자부터_받는다() {
		assertThat(PasswordPolicy.check("123456789", null)).contains(Violation.TOO_SHORT);
		assertThat(PasswordPolicy.check("1234567890", null)).isEmpty();
	}

	@Test
	void 비었거나_공백뿐이면_받지_않는다() {
		assertThat(PasswordPolicy.check(null, null)).contains(Violation.TOO_SHORT);
		assertThat(PasswordPolicy.check("", null)).contains(Violation.TOO_SHORT);
		assertThat(PasswordPolicy.check("            ", null)).contains(Violation.TOO_SHORT);
	}

	// 눈에 보이지 않는 글자로 길이만 채운 값이다. 빈 값과 다를 바 없다.
	@Test
	void 보이지_않는_글자로_길이를_채운_값은_받지_않는다() {
		String noBreakSpaces = " ".repeat(12);
		String zeroWidthSpaces = "​".repeat(12);

		assertThat(PasswordPolicy.check(noBreakSpaces, null)).contains(Violation.TOO_SHORT);
		assertThat(PasswordPolicy.check(zeroWidthSpaces, null)).contains(Violation.TOO_SHORT);
		assertThat(PasswordPolicy.check("abc" + zeroWidthSpaces, null)).contains(Violation.TOO_SHORT);
	}

	// 낱말 사이의 공백은 된다. 긴 문장을 비밀번호로 쓸 수 있어야 한다.
	@Test
	void 공백이_섞인_문장은_받는다() {
		assertThat(PasswordPolicy.check("correct horse battery", null)).isEmpty();
		assertThat(PasswordPolicy.check("우리 학원 데스크 비밀번호", null)).isEmpty();
	}

	// 해시 함수는 널 문자에서 읽기를 멈춘다. 널 문자가 든 14자 비밀번호는 앞의 4자만으로 로그인된다.
	@Test
	void 제어_문자가_든_값은_받지_않는다() {
		assertThat(PasswordPolicy.check("abcd\u0000abcd\u0000abcd", null)).contains(Violation.CONTROL_CHARACTER);
		assertThat(PasswordPolicy.check("first-line\nsecond-line", null)).contains(Violation.CONTROL_CHARACTER);
		assertThat(PasswordPolicy.check("tab\tseparated-password", null)).contains(Violation.CONTROL_CHARACTER);
	}

	// BCrypt는 72바이트까지만 읽는다. 넘는 부분이 조용히 버려지면 뒤쪽이 다른 비밀번호로도 로그인된다.
	@Test
	void 일흔두_바이트까지_받는다() {
		assertThat(PasswordPolicy.check("a".repeat(72), null)).isEmpty();
		assertThat(PasswordPolicy.check("a".repeat(73), null)).contains(Violation.TOO_LONG);
	}

	// 한글은 한 글자가 3바이트다. 글자 수로는 짧아 보여도 넘을 수 있다.
	@Test
	void 길이는_글자_수가_아니라_바이트로_잰다() {
		assertThat(PasswordPolicy.check("가".repeat(24), null)).isEmpty();
		assertThat(PasswordPolicy.check("가".repeat(25), null)).contains(Violation.TOO_LONG);
	}

	@Test
	void 글자_종류는_가리지_않는다() {
		assertThat(PasswordPolicy.check("aaaaaaaaaa", null)).isEmpty();
		assertThat(PasswordPolicy.check("!@#$%^&*()_+", null)).isEmpty();
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
