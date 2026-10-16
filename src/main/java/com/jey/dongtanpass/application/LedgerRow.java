package com.jey.dongtanpass.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import com.jey.core.shared.Money;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassState;
import com.jey.dongtanpass.domain.PaymentMethod;

/**
 * 발급 장부의 한 줄. 학부모 번호는 없다(색인뿐이고 장부에 쓸 일이 없다).
 *
 * @param issuedOn 발급일(한국 날짜)
 * @param state 오늘 기준으로 보이는 상태
 */
public record LedgerRow(long id, String passNo, String studentName, Instant issuedAt, LocalDate issuedOn,
		LocalDate expiresOn, PassState state, LocalDate paidOn, Money paidAmount, PaymentMethod paymentMethod) {

	static LedgerRow of(Pass pass, LocalDate today, ZoneId zone) {
		return new LedgerRow(pass.getId(), pass.getPassNo(), pass.getStudentName(), pass.getIssuedAt(),
				pass.getIssuedAt().atZone(zone).toLocalDate(), pass.getExpiresOn(), pass.stateOn(today),
				pass.getPaidOn(), pass.getPaidAmount(), pass.getPaymentMethod());
	}

	// 학생 이름이 로그에 찍히지 않게 한다.
	@Override
	public String toString() {
		return "LedgerRow[id=" + id + ", passNo=" + passNo + ", state=" + state + "]";
	}

}
