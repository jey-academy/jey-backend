package com.jey.billing.api;

import java.util.Collection;
import java.util.Set;

import com.jey.core.shared.BusinessException;

/**
 * 다른 모듈이 근거(참조값)가 있는 할인을 실어 청구를 만드는 창구. 예: 동탄고 패스를 쓴 학생의 청구에 패스 할인과 패스 번호를 붙인다.
 * 그런 할인(설정 {@code jey.billing.referenced-discount-codes})은 직원이 고르는 일반 경로로는 붙지 않는다.
 *
 * 청구가 열려 있는지도 여기서 묻는다({@link #isOpen}, {@link #openAmong}).
 *
 * <p>부르는 쪽의 트랜잭션에 참여한다(부르는 쪽이 롤백하면 청구도 없어진다). 누가 그 학생을 다룰 수 있는지(지점)는 부르는 쪽이 본다.
 * billing은 학생(재원 중), 반(켜짐, 학생과 같은 지점), 할인 유형(켜짐), 한 학생 한 달 열린 청구 하나를 본다.
 */
public interface InvoiceIssuing {

	/**
	 * 예외가 나면 부르는 쪽의 트랜잭션은 롤백으로 표시된다. 잡아서 계속 진행하지 않는다(커밋할 때 실패한다).
	 *
	 * @throws BusinessException 학생·반·할인이 규칙 밖이거나, 근거가 필요한 할인을 직원 선택 칸으로 보냈거나, 그 학생의 그 달 청구가
	 *     이미 있을 때(일반 경로와 같은 코드)
	 * @throws IllegalArgumentException 명령의 값이 {@link InvoiceRules} 밖이거나, 근거 할인 칸에 근거가 필요하지 않은 할인을 실었을 때
	 *     (부르는 쪽의 버그)
	 */
	IssuedInvoice issue(IssueInvoiceCommand command);

	/** 열린 청구(결제 전이거나 결제함)인지. 취소했거나 없는 청구면 {@code false}. */
	boolean isOpen(long invoiceId);

	/**
	 * 그 가운데 열린 청구(결제 전이거나 결제함)의 ID. 취소했거나 없는 청구는 빠지고, 같은 ID는 한 번으로 본다.
	 * 목록 한 페이지 분량(수십~백 개)에 쓴다(줄마다 {@link #isOpen}을 부르지 않게). 빈 목록이면 DB를 부르지 않고 빈 집합이다.
	 * 돌려주는 집합은 바꿀 수 없고 {@code contains(null)}에 예외를 던진다(청구가 없는 줄은 묻기 전에 거른다).
	 *
	 * @throws NullPointerException 목록이 {@code null}이거나 {@code null} ID가 섞였을 때(부르는 쪽의 버그)
	 */
	Set<Long> openAmong(Collection<Long> invoiceIds);

}
