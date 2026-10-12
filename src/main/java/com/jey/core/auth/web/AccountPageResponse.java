package com.jey.core.auth.web;

import java.util.List;

import com.jey.core.auth.AccountView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import org.springframework.data.domain.Page;

@Schema(description = "계정 목록의 한 페이지. 아이디 순이고 차단된 계정도 포함한다")
record AccountPageResponse(@Schema(requiredMode = RequiredMode.REQUIRED) List<AccountResponse> items,
		@Schema(description = "지금 페이지. 0부터", requiredMode = RequiredMode.REQUIRED) int page,
		@Schema(description = "한 페이지의 크기", requiredMode = RequiredMode.REQUIRED) int size,
		@Schema(description = "조건에 맞는 전체 계정 수", requiredMode = RequiredMode.REQUIRED) long totalElements,
		@Schema(requiredMode = RequiredMode.REQUIRED) int totalPages) {

	static AccountPageResponse from(Page<AccountView> page) {
		return new AccountPageResponse(page.getContent().stream().map(AccountResponse::from).toList(),
				page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
	}

}
