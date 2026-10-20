package com.jey.billing.domain.ledger;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import com.jey.core.shared.Money;
import com.jey.core.shared.PhoneNumber;

/**
 * 장부의 한 줄에 쓸 결제 한 건. 이름과 원문 전화번호를 가지므로 {@link #toString()}은 결제 ID만 보인다.
 *
 * @param paymentId 결제 ID(비고 칸의 표시)
 * @param schoolLabel 학교와 학년({@code 동탄고1}). 없으면 {@code null} — 학교 칸을 쓰지 않는다
 * @param studentName 학생 이름
 * @param className 반 이름(장부의 반 코드와 같다)
 * @param phone 대표 연락처
 * @param targetMonth 청구의 대상 월
 * @param sessions 수업 횟수
 * @param discountNames 적용한 할인 유형의 이름(순서대로). 없으면 빈 목록
 * @param chargeAmount 청구 합계(납입금액 칸)
 * @param monthlyBase 같은 할인으로 8회를 계산한 금액(매월 납입금액 칸)
 * @param paidOn 결제일(한국 날짜)
 * @param tuition 받은 수강료(수강료 칸)
 * @param bookFee 받은 교재비. 0원이면 교재비 칸을 쓰지 않는다
 */
public record LedgerEntry(long paymentId, String schoolLabel, String studentName, String className, PhoneNumber phone,
		YearMonth targetMonth, int sessions, List<String> discountNames, Money chargeAmount, Money monthlyBase,
		LocalDate paidOn, Money tuition, Money bookFee) {

	/**
	 * @throws IllegalArgumentException 학교 표시 말고 비어 있는 값이 있거나 횟수가 1보다 작을 때
	 */
	public LedgerEntry {
		if (studentName == null || className == null || phone == null || targetMonth == null || discountNames == null
				|| chargeAmount == null || monthlyBase == null || paidOn == null || tuition == null || bookFee == null) {
			throw new IllegalArgumentException("학교 표시 말고는 비울 수 없다");
		}
		if (sessions < 1) {
			throw new IllegalArgumentException("수업 횟수는 1 이상이다");
		}
		discountNames = List.copyOf(discountNames);
	}

	@Override
	public String toString() {
		return "LedgerEntry[paymentId=" + paymentId + "]";
	}

}
