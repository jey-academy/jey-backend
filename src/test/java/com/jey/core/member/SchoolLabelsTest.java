package com.jey.core.member;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.jey.core.member.domain.Grade;

// 장부의 학교 칸 모양(동탄고1). 학년은 숫자만 붙인다.
class SchoolLabelsTest {

	@Test
	void 학교_뒤에_학년_숫자를_붙인다() {
		assertThat(SchoolLabels.of("동탄고", Grade.HIGH_1)).isEqualTo("동탄고1");
		assertThat(SchoolLabels.of("동탄중", Grade.MIDDLE_3)).isEqualTo("동탄중3");
		assertThat(SchoolLabels.of("동탄초", Grade.ELEM_6)).isEqualTo("동탄초6");
	}

	@Test
	void 학년이_기타면_학교만_쓴다() {
		assertThat(SchoolLabels.of("동탄고", Grade.ETC)).isEqualTo("동탄고");
	}

	@Test
	void 학교가_없으면_표시도_없다() {
		assertThat(SchoolLabels.of(null, Grade.HIGH_1)).isNull();
	}

}
