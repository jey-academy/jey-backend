package com.jey.core.member.web;

import com.jey.core.member.StudentView.ContactView;
import com.jey.core.member.domain.GuardianRelation;
import com.jey.core.shared.PhoneNumber;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

@Schema(description = "대표 연락처. 어머니, 먼저 연결된 학부모, 학생 본인 번호 순으로 고른다")
record ContactResponse(@Schema(requiredMode = RequiredMode.REQUIRED) String name,
		@Schema(description = "학부모의 관계. 학생 본인 번호면 null") GuardianRelation relation,
		@Schema(description = "가운데를 가린 전화번호", example = "010-****-5678",
				requiredMode = RequiredMode.REQUIRED) PhoneNumber phone) {

	static ContactResponse from(ContactView contact) {
		return (contact == null) ? null : new ContactResponse(contact.name(), contact.relation(), contact.phone());
	}

}
