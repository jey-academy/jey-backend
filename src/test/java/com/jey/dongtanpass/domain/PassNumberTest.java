package com.jey.dongtanpass.domain;

import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class PassNumberTest {

	// 데스크에서 눈으로 읽고 손으로 친다. 헷갈리는 글자(0/O, 1/I)는 쓰지 않는다.
	@Test
	void 글자_집합에는_헷갈리는_글자가_없다() {
		assertThat(PassNumber.ALPHABET).hasSize(32).doesNotContain("0", "O", "1", "I");
		assertThat(PassNumber.ALPHABET.chars().distinct().count()).isEqualTo(32);
	}

	@Test
	void 만든_번호는_DT와_여섯_글자이고_형식_확인을_지난다() {
		RandomGenerator random = new SplittableRandom(7);
		for (int i = 0; i < 1_000; i++) {
			String number = PassNumber.generate(random);

			assertThat(number).matches("DT-[A-HJ-NP-Z2-9]{6}");
			assertThat(PassNumber.isValid(number)).isTrue();
		}
	}

	@Test
	void 같은_난수에서는_같은_번호가_나온다() {
		assertThat(PassNumber.generate(new SplittableRandom(1))).isEqualTo(PassNumber.generate(new SplittableRandom(1)));
	}

	@Test
	void 글자_집합의_모든_글자가_나온다() {
		RandomGenerator random = new SplittableRandom(3);
		StringBuilder seen = new StringBuilder();
		for (int i = 0; i < 2_000; i++) {
			seen.append(PassNumber.generate(random).substring(3));
		}
		assertThat(PassNumber.ALPHABET.chars()).allMatch(c -> seen.indexOf(Character.toString(c)) >= 0);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "DT-ABC23O", "DT-ABC231", "dt-abc234", "DT-ABC2345", "DT-ABC23", "XX-ABC234", "DTABC234",
			" DT-ABC234" })
	void 형식에_맞지_않는_번호는_거부한다(String value) {
		assertThat(PassNumber.isValid(value)).isFalse();
	}

}
