package com.jey.core.member.web;

import com.jey.core.member.domain.Student;
import com.jey.core.shared.PageLimits;
import com.jey.core.shared.RejectUnknownFields;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 학생 검색의 조건. 모두 선택이고, 조건이 없으면 빈 객체를 보낸다.
 * 주소가 아니라 본문으로 받는다. 이름과 전화번호 뒷자리가 주소에 실리면 브라우저의 방문 기록과 앞단의 접근 로그에 남는다 (ADR-0032).
 *
 * <p>모르는 필드가 있으면 거부한다. 조건의 이름을 잘못 적은 요청이 조건 없는 전체 목록으로 답해지지 않게 한다.
 *
 * <p>페이지와 크기의 기본값은 명세에 {@code default}로 적지 않고 설명에만 적는다. {@code default}를 적으면 프론트의 타입 생성기가
 * 그 필드를 필수로 만들어, 조건 없이 빈 객체를 보내는 호출이 타입 오류가 된다.
 */
@RejectUnknownFields
record StudentSearchRequest(
		@Schema(description = "지점. 없으면 전 지점") Long campusId,
		@Schema(description = "이름의 앞부분") @Size(max = Student.MAX_NAME_LENGTH) String name,
		@Schema(description = "학부모나 학생 본인 전화번호의 뒷자리 4자리. 같은 뒷자리를 가진 학생이 모두 나온다") @Pattern(regexp = "\\d{4}",
				message = "숫자 4자리여야 합니다.") String phoneLast4,
		@Schema(description = PageLimits.PAGE_DESCRIPTION + ". 없으면 0") @Min(0) @Max(PageLimits.MAX_PAGE) Integer page,
		@Schema(description = "한 페이지의 크기. " + StudentSearchRequest.MAX_PAGE_SIZE + "까지. 없으면 "
				+ StudentSearchRequest.DEFAULT_PAGE_SIZE) @Min(1) @Max(StudentSearchRequest.MAX_PAGE_SIZE) Integer size) {

	static final int DEFAULT_PAGE_SIZE = 20;

	static final int MAX_PAGE_SIZE = 100;

	StudentSearchRequest {
		page = (page == null) ? 0 : page;
		size = (size == null) ? DEFAULT_PAGE_SIZE : size;
	}

	// 요청을 로그에 그대로 찍는 실수를 해도 검색어가 남지 않게 한다.
	@Override
	public String toString() {
		return "StudentSearchRequest[campusId=" + campusId + ", name=" + ((name != null) ? "(가림)" : null)
				+ ", phoneLast4=" + ((phoneLast4 != null) ? "(가림)" : null) + ", page=" + page + ", size=" + size + "]";
	}

}
