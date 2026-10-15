package com.jey.dongtanpass.web;

import com.jey.dongtanpass.domain.Partner;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "연계 학원 만들기. 켜진 학원으로 만든다")
record PartnerCreateRequest(
		@Schema(description = "학원 이름. 앞뒤 공백은 뗀다. 다른 학원과 겹칠 수 없다(대소문자만 다른 것도 겹친다)") @NotBlank @Size(
				max = Partner.MAX_NAME_LENGTH) String name) {

}
