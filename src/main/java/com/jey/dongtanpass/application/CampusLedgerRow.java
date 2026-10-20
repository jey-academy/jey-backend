package com.jey.dongtanpass.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import com.jey.core.shared.Money;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassState;
import com.jey.dongtanpass.domain.PaymentMethod;

/**
 * JEY 발급 장부의 한 줄. 학부모 번호는 없다.
 *
 * @param discountRate 발급 때 남긴 할인율
 */
public record CampusLedgerRow(long id, String passNo, long studentId, String studentName, Instant issuedAt,
		LocalDate issuedOn, LocalDate expiresOn, PassState state, BigDecimal discountRate, LocalDate paidOn,
		Money paidAmount, PaymentMethod paymentMethod) {

	static CampusLedgerRow of(Pass pass, LocalDate today, ZoneId zone) {
		return new CampusLedgerRow(pass.getId(), pass.getPassNo(), pass.getStudentId(), pass.getStudentName(),
				pass.getIssuedAt(), pass.issuedOn(zone), pass.getExpiresOn(), pass.stateOn(today),
				pass.getDiscountRate(), pass.getPaidOn(), pass.getPaidAmount(), pass.getPaymentMethod());
	}

	// 학생 이름이 로그에 찍히지 않게 한다.
	@Override
	public String toString() {
		return "CampusLedgerRow[id=" + id + ", passNo=" + passNo + ", state=" + state + "]";
	}

}
