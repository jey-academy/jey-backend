package com.jey.dongtanpass.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

}
