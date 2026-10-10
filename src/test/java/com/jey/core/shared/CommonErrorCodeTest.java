package com.jey.core.shared;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class CommonErrorCodeTest {

	@Test
	void 코드는_COMMON_접두사와_상수_이름으로_만든다() {
		assertThat(CommonErrorCode.VALIDATION_FAILED.code()).isEqualTo("COMMON_VALIDATION_FAILED");
		assertThat(Arrays.stream(CommonErrorCode.values()).map(CommonErrorCode::code))
				.allMatch(code -> code.startsWith("COMMON_"))
				.doesNotHaveDuplicates();
	}

	@Test
	void 모든_코드에_안내_문구가_있다() {
		assertThat(CommonErrorCode.values()).allSatisfy(code -> assertThat(code.message()).isNotBlank());
	}

	@Test
	void HTTP_상태로_공통_코드를_찾는다() {
		assertThat(CommonErrorCode.fromStatus(HttpStatus.UNAUTHORIZED)).isEqualTo(CommonErrorCode.UNAUTHENTICATED);
		assertThat(CommonErrorCode.fromStatus(HttpStatus.FORBIDDEN)).isEqualTo(CommonErrorCode.FORBIDDEN);
		assertThat(CommonErrorCode.fromStatus(HttpStatus.NOT_FOUND)).isEqualTo(CommonErrorCode.NOT_FOUND);
		assertThat(CommonErrorCode.fromStatus(HttpStatus.METHOD_NOT_ALLOWED)).isEqualTo(CommonErrorCode.METHOD_NOT_ALLOWED);
		assertThat(CommonErrorCode.fromStatus(HttpStatus.CONFLICT)).isEqualTo(CommonErrorCode.CONFLICT);
	}

	@Test
	void 매핑이_없는_4xx는_BAD_REQUEST_5xx는_INTERNAL_ERROR() {
		assertThat(CommonErrorCode.fromStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)).isEqualTo(CommonErrorCode.BAD_REQUEST);
		assertThat(CommonErrorCode.fromStatus(HttpStatus.SERVICE_UNAVAILABLE)).isEqualTo(CommonErrorCode.INTERNAL_ERROR);
	}

}
