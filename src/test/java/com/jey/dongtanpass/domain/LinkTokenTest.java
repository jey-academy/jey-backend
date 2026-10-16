package com.jey.dongtanpass.domain;

import java.security.SecureRandom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class LinkTokenTest {

	private static final SecureRandom RANDOM = new SecureRandom();

	@Test
	void 원문은_43자_Base64url이고_해시는_64자_소문자_16진수다() {
		LinkToken token = LinkToken.generate(RANDOM);

		assertThat(token.raw()).matches("[A-Za-z0-9_-]{43}");
		assertThat(token.hash()).matches("[0-9a-f]{64}");
	}

	// 다음 이슈(학부모 열람)가 받은 원문으로 해시를 다시 계산해 패스를 찾는다.
	@Test
	void 원문의_해시는_만들_때의_해시와_같다() {
		LinkToken token = LinkToken.generate(RANDOM);

		assertThat(LinkToken.hashOf(token.raw())).isEqualTo(token.hash());
	}

	@Test
	void SHA_256_해시다() {
		// echo -n "abc" | sha256sum
		assertThat(LinkToken.hashOf("abc"))
			.isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
	}

	@Test
	void 만들_때마다_다르다() {
		assertThat(LinkToken.generate(RANDOM).raw()).isNotEqualTo(LinkToken.generate(RANDOM).raw());
	}

	// 받은 토큰은 해시하기 전에 모양을 본다. 틀린 모양은 없는 토큰과 같이 답한다(학부모 열람).
	@Test
	void 만든_원문은_형식에_맞는다() {
		assertThat(LinkToken.isWellFormed(LinkToken.generate(RANDOM).raw())).isTrue();
		assertThat(LinkToken.isWellFormed("A".repeat(43))).isTrue();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "x", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
			"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA+",
			"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/", " AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
			"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\n", "가AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" })
	void 형식이_틀린_원문은_거부한다(String raw) {
		assertThat(LinkToken.isWellFormed(raw)).isFalse();
	}

	// 원문은 응답에 한 번만 나간다. 로그에 찍히지 않게 한다.
	@Test
	void 문자열로_바꿔도_원문은_없다() {
		LinkToken token = LinkToken.generate(RANDOM);

		assertThat(token.toString()).doesNotContain(token.raw()).contains(token.hash());
	}

}
