package com.jey.dongtanpass.domain;

import java.text.Normalizer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StudentNameKeyTest {

	// 연계 학원이 적은 이름과 학부모·직원이 넣은 이름은 띄어쓰기가 다를 수 있다.
	@ParameterizedTest(name = "[{0}]")
	@ValueSource(strings = { "김하윤", "김 하윤", " 김하윤 ", "김 하 윤", "김　하윤", "김 하윤", "김\t하윤", "김\n하윤" })
	void 공백이_달라도_맞는다(String input) {
		assertThat(StudentNameKey.of(input).matches("김 하윤")).isTrue();
	}

	// 붙여 넣은 이름은 한글이 자모로 풀려(NFD) 들어올 수 있다. 저장된 쪽이 풀려 있어도 같다.
	@Test
	void 자모로_풀린_이름도_맞는다() {
		String decomposed = Normalizer.normalize("김하윤", Normalizer.Form.NFD);

		assertThat(StudentNameKey.of(decomposed).matches("김하윤")).isTrue();
		assertThat(StudentNameKey.of("김하윤").matches(decomposed)).isTrue();
	}

	@ParameterizedTest(name = "[{0}]")
	@ValueSource(strings = { "김하은", "김하", "김하윤윤", "Kim Hayun", "" })
	void 다른_글자면_맞지_않는다(String stored) {
		assertThat(StudentNameKey.of("김하윤").matches(stored)).isFalse();
	}

	@Test
	void 저장된_이름이_없으면_맞지_않는다() {
		assertThat(StudentNameKey.of("김하윤").matches(null)).isFalse();
	}

	@ParameterizedTest(name = "[{0}]")
	@ValueSource(strings = { "", " ", "　 \t" })
	void 공백뿐인_이름은_받지_않는다(String input) {
		assertThatThrownBy(() -> StudentNameKey.of(input)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 이름이_없으면_받지_않는다() {
		assertThatThrownBy(() -> StudentNameKey.of(null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void 같은_이름은_같은_값이다() {
		assertThat(StudentNameKey.of("김 하윤")).isEqualTo(StudentNameKey.of("김하윤"))
			.hasSameHashCodeAs(StudentNameKey.of("김하윤"));
	}

	// 학생 실명은 로그에 싣지 않는다.
	@Test
	void 문자열로_바꿔도_이름이_나오지_않는다() {
		assertThat(StudentNameKey.of("김하윤").toString()).doesNotContain("김", "하", "윤");
	}

}
