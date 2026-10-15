package com.jey.dongtanpass.web;

import com.jey.core.auth.api.AccountRules;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "연계 학원의 계정 만들기. 그 학원의 공용 계정이다. 소속은 만든 뒤 바꿀 수 없다")
record PartnerAccountCreateRequest(
		@Schema(description = "로그인 아이디. 영문 소문자, 숫자, 점, 밑줄, 하이픈으로 3~50자이고 첫 글자는 영문이나 숫자다. 만든 뒤에는 바꿀 수 없다",
				example = "sunshine.math") @NotBlank @Pattern(regexp = AccountRules.LOGIN_ID_PATTERN,
						message = "영문 소문자, 숫자, 점, 밑줄, 하이픈으로 3~50자여야 합니다.") String loginId,
		@Schema(description = "화면에 보일 이름") @NotBlank @Size(max = AccountRules.MAX_NAME_LENGTH) String name,
		@Schema(description = "처음 비밀번호. 10자 이상 72자 이하이고(한글은 24자까지) 아이디와 달라야 한다. 연계 학원이 로그인한 뒤 직접 바꾼다") @NotBlank @Size(
				max = AccountRules.MAX_PASSWORD_INPUT_LENGTH) String password) {

	// 비밀번호가 로그에 찍히지 않게 한다.
	@Override
	public String toString() {
		return "PartnerAccountCreateRequest[loginId=" + loginId + "]";
	}

}
