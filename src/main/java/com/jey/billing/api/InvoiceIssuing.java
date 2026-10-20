package com.jey.billing.api;

import com.jey.core.shared.BusinessException;

/**
 * 다른 모듈이 근거(참조값)가 있는 할인을 실어 청구를 만드는 창구. 예: 동탄고 패스를 쓴 학생의 청구에 패스 할인과 패스 번호를 붙인다.
 * 그런 할인(설정 {@code jey.billing.referenced-discount-codes})은 직원이 고르는 일반 경로로는 붙지 않는다.
 *
 * <p>부르는 쪽의 트랜잭션에 참여한다(부르는 쪽이 롤백하면 청구도 없어진다). 누가 그 학생을 다룰 수 있는지(지점)는 부르는 쪽이 본다.
 * billing은 학생(재원 중), 반(켜짐, 학생과 같은 지점), 할인 유형(켜짐), 한 학생 한 달 열린 청구 하나를 본다.
 */
public interface InvoiceIssuing {

	/**
	 * @throws BusinessException 학생·반·할인이 규칙 밖이거나, 그 학생의 그 달 청구가 이미 있을 때(일반 경로와 같은 코드)
	 * @throws IllegalArgumentException 명령의 값이 규칙 밖일 때(반 개수, 횟수, 대상 월, 같은 할인 두 번 — 부르는 쪽의 버그)
	 */
	IssuedInvoice issue(IssueInvoiceCommand command);

	/** 열린 청구(결제 전이거나 결제함)인지. 취소했거나 없는 청구면 {@code false}. */
	boolean isOpen(long invoiceId);

}
