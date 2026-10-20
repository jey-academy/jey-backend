package com.jey.dongtanpass.application;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 연계 학원이 확인한 JEY 발급 패스. 학생 이름은 가린 것이고 학부모 번호는 없다.
 *
 * @param campusName 패스를 발급한 JEY 지점
 * @param discountRate 발급 때 남긴 할인율
 */
public record PartnerVerifiedPassView(long passId, String passNo, String campusName, String maskedStudentName,
		LocalDate issuedOn, LocalDate expiresOn, BigDecimal discountRate) {
}
