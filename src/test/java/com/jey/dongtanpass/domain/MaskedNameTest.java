package com.jey.dongtanpass.domain;

import java.text.Normalizer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class MaskedNameTest {

	// 학부모 화면은 링크만 있으면 누구나 연다. 이름을 다 보여 주지 않는다.
	@ParameterizedTest(name = "{0} → {1}")
	@CsvSource(delimiter = '|', value = { "김|김", "김하|김*", "김하윤|김*윤", "남궁민수|남**수", "김 하윤|김*윤", "' 김하윤 '|김*윤",
			"Kim Ha|K***a" })
	void 공백을_빼고_첫_글자와_끝_글자만_남긴다(String name, String masked) {
		assertThat(MaskedName.of(name)).isEqualTo(masked);
	}

	@Test
	void 서리게이트_쌍도_한_글자로_센다() {
		assertThat(MaskedName.of("😀하😀")).isEqualTo("😀*😀");
		assertThat(MaskedName.of("😀😀")).isEqualTo("😀*");
	}

	@Test
	void 전각_공백도_뺀다() {
		assertThat(MaskedName.of("김　하윤")).isEqualTo("김*윤");
	}

	// 붙여 넣은 이름은 한글이 자모로 풀려(NFD) 들어올 수 있다. 음절로 모은 뒤 센다.
	@Test
	void 자모로_풀린_이름도_음절로_센다() {
		String decomposed = Normalizer.normalize("김하윤", Normalizer.Form.NFD);

		assertThat(decomposed).hasSize(8);
		assertThat(MaskedName.of(decomposed)).isEqualTo("김*윤");
	}

	// 저장된 이름은 비어 있지 않다(Pass가 막는다). 들어오더라도 예외 없이 빈 문자열이다.
	@ParameterizedTest
	@ValueSource(strings = { "", " ", "　" })
	void 비었거나_공백뿐이면_빈_문자열이다(String name) {
		assertThat(MaskedName.of(name)).isEmpty();
	}

}
