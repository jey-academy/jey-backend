package com.jey.core.shared;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

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

	@Test
	void 문구가_비어_있으면_에러코드의_기본_문구를_쓴다() {
		assertThat(new BusinessException(CommonErrorCode.CONFLICT, null).getMessage())
				.isEqualTo(CommonErrorCode.CONFLICT.message());
		assertThat(new BusinessException(CommonErrorCode.CONFLICT, "  ").getMessage())
				.isEqualTo(CommonErrorCode.CONFLICT.message());
	}

	@Test
	void 에러코드가_없으면_만들_수_없다() {
		assertThatNullPointerException()
				.isThrownBy(() -> new BusinessException(null, "문구"))
				.withMessage("errorCode");
	}

	@Test
	void 원인_예외를_담을_수_있다() {
		var cause = new IllegalStateException("PG timeout");

		var exception = new BusinessException(CommonErrorCode.INTERNAL_ERROR, null, cause);

		assertThat(exception.getCause()).isSameAs(cause);
		assertThat(exception.getMessage()).isEqualTo(CommonErrorCode.INTERNAL_ERROR.message());
	}

}
