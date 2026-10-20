package com.jey.dongtanpass.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.jey.core.shared.Money;
import com.jey.dongtanpass.application.CampusLedgerRow;
import com.jey.dongtanpass.domain.PassState;
import com.jey.dongtanpass.domain.PaymentMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import org.springframework.data.domain.Page;

record CampusPassPageResponse(@Schema(requiredMode = RequiredMode.REQUIRED) List<CampusPassItemResponse> items,
		@Schema(description = "지금 페이지. 0부터", requiredMode = RequiredMode.REQUIRED) int page,
		@Schema(description = "한 페이지의 크기", requiredMode = RequiredMode.REQUIRED) int size,
		@Schema(description = "조건에 맞는 전체 건수", requiredMode = RequiredMode.REQUIRED) long totalElements,
		@Schema(requiredMode = RequiredMode.REQUIRED) int totalPages) {

	static CampusPassPageResponse from(Page<CampusLedgerRow> page) {
		return new CampusPassPageResponse(page.getContent().stream().map(CampusPassItemResponse::from).toList(),
				page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
	}

	@Schema(description = "JEY가 발급한 패스 하나. 학부모 번호는 없다")
	record CampusPassItemResponse(
			@Schema(description = "패스 ID. 링크 새로 만들기와 취소에 쓴다", requiredMode = RequiredMode.REQUIRED) Long id,
			@Schema(description = "패스 번호", requiredMode = RequiredMode.REQUIRED) String passNo,
			@Schema(description = "학생 ID", requiredMode = RequiredMode.REQUIRED) Long studentId,
			@Schema(description = "발급 때의 학생 이름", requiredMode = RequiredMode.REQUIRED) String studentName,
			@Schema(description = "발급 시각", requiredMode = RequiredMode.REQUIRED) Instant issuedAt,
			@Schema(description = "발급일(한국 날짜). 기간 조건은 이 날짜로 자른다", requiredMode = RequiredMode.REQUIRED) LocalDate issuedOn,
			@Schema(description = "이날(한국 날짜)까지 쓸 수 있다", requiredMode = RequiredMode.REQUIRED) LocalDate expiresOn,
			@Schema(description = "오늘 기준으로 보이는 상태. EXPIRED는 쓰지 않은 채 만료일이 지난 패스",
					requiredMode = RequiredMode.REQUIRED) PassState state,
			@Schema(description = "이 패스의 할인율(발급 때의 설정). 5%는 0.05", requiredMode = RequiredMode.REQUIRED) BigDecimal discountRate,
			@Schema(description = "근거 결제의 결제일", requiredMode = RequiredMode.REQUIRED) LocalDate paidOn,
			@Schema(description = "근거 결제의 금액(원)", requiredMode = RequiredMode.REQUIRED) Money paidAmount,
			@Schema(description = "근거 결제의 수단", requiredMode = RequiredMode.REQUIRED) PaymentMethod paymentMethod) {

		static CampusPassItemResponse from(CampusLedgerRow row) {
			return new CampusPassItemResponse(row.id(), row.passNo(), row.studentId(), row.studentName(),
					row.issuedAt(), row.issuedOn(), row.expiresOn(), row.state(), row.discountRate(), row.paidOn(),
					row.paidAmount(), row.paymentMethod());
		}

		// 학생 이름이 로그에 찍히지 않게 한다. 바깥 record도 이것을 쓴다.
		@Override
		public String toString() {
			return "CampusPassItemResponse[id=" + id + ", passNo=" + passNo + ", state=" + state + "]";
		}

	}

}
