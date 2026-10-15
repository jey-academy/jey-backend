package com.jey.dongtanpass.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConsentNoticeTest {

	// 버전은 동의 기록 칸(20자)에 들어가야 한다.
	@Test
	void 지금_버전은_비어_있지_않고_20자_안이다() {
		assertThat(ConsentNotice.CURRENT.version()).isNotBlank().hasSizeLessThanOrEqualTo(ConsentNotice.MAX_VERSION_LENGTH);
		assertThat(ConsentNotice.MAX_VERSION_LENGTH).isEqualTo(20);
	}

	@Test
	void 문구가_비어_있지_않다() {
		assertThat(ConsentNotice.CURRENT.text()).isNotBlank();
	}

	@Test
	void 지금_버전인지_가린다() {
		assertThat(ConsentNotice.isCurrent(ConsentNotice.CURRENT.version())).isTrue();
		assertThat(ConsentNotice.isCurrent("2000-01-v0")).isFalse();
		assertThat(ConsentNotice.isCurrent(null)).isFalse();
	}

}
