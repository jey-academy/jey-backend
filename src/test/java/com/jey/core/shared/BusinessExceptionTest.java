package com.jey.core.shared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.entry;

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

	// 오류 본문에 덧붙일 값은 ID만이다(예: 이미 있는 청구로 바로 가게). 개인정보를 실을 자리를 만들지 않는다.
	@Test
	void 오류_본문에_덧붙일_ID를_순서대로_담는다() {
		var exception = new BusinessException(CommonErrorCode.CONFLICT).withId("invoiceId", 42L).withId("studentId", 7L);

		assertThat(exception.ids()).containsExactly(entry("invoiceId", 42L), entry("studentId", 7L));
	}

	@Test
	void 덧붙일_ID가_없으면_비어_있다() {
		assertThat(new BusinessException(CommonErrorCode.CONFLICT).ids()).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "invoice", "InvoiceId", "id", "requestId", "invoice_id", "invoiceID", "" })
	void ID의_이름은_소문자로_시작하고_Id로_끝나며_예약된_칸이_아니어야_한다(String name) {
		var exception = new BusinessException(CommonErrorCode.CONFLICT);

		assertThatIllegalArgumentException().isThrownBy(() -> exception.withId(name, 1L));
	}

	@Test
	void 이름이_없거나_같은_이름을_두_번_담으면_코드의_잘못이다() {
		var exception = new BusinessException(CommonErrorCode.CONFLICT).withId("invoiceId", 1L);

		assertThatIllegalArgumentException().isThrownBy(() -> exception.withId(null, 1L));
		assertThatIllegalArgumentException().isThrownBy(() -> exception.withId("invoiceId", 2L));
	}

}
