package com.jey;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MainClassesTest {

	@Test
	void 한_번만_읽어_같은_것을_돌려준다() {
		assertThat(MainClasses.get()).isSameAs(MainClasses.get());
	}

	@Test
	void 운영_코드만_담는다() {
		assertThat(MainClasses.get().contain(JeyBackendApplication.class)).isTrue();
		assertThat(MainClasses.get().contain(MainClassesTest.class)).isFalse();
	}

}
