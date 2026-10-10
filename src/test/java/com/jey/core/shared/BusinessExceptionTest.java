package com.jey.core.shared;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessExceptionTest {

	@Test
	void 문구를_주지_않으면_에러코드의_기본_문구를_쓴다() {
		var exception = new BusinessException(CommonErrorCode.NOT_FOUND);

		assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.NOT_FOUND);
		assertThat(exception.getMessage()).isEqualTo(CommonErrorCode.NOT_FOUND.message());
	}

	@Test
	void 문구를_주면_그_문구를_쓴다() {
		var exception = new BusinessException(CommonErrorCode.CONFLICT, "이미 사용한 패스입니다.");

		assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.CONFLICT);
		assertThat(exception.getMessage()).isEqualTo("이미 사용한 패스입니다.");
	}

}
