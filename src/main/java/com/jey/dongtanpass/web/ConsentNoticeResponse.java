package com.jey.dongtanpass.web;

import com.jey.dongtanpass.application.ConsentNotice;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "발급 화면에 보여 줄 고지 문구")
record ConsentNoticeResponse(
		@Schema(description = "문구의 버전. 발급 요청의 noticeVersion에 그대로 보낸다", requiredMode = RequiredMode.REQUIRED) String version,
		@Schema(description = "학부모에게 보여 줄 문구. 여러 줄이다(줄바꿈 문자로 나뉜다)", requiredMode = RequiredMode.REQUIRED) String text) {

	static ConsentNoticeResponse from(ConsentNotice notice) {
		return new ConsentNoticeResponse(notice.version(), notice.text());
	}

}
