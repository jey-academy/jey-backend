package com.jey.dongtanpass.application;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 데스크가 확인한 패스. 확인을 통과했으므로 늘 쓸 수 있는 패스다. 학생 이름은 가린 것이고 학부모 번호는 없다.
 *
 * @param passId 사용 처리(본인 확인, 사용)에 쓰는 패스 ID
 * @param maskedStudentName 첫 글자와 끝 글자만 남긴 이름. 직원이 등록 폼에 적은 이름은 사용 처리에서 맞춰 본다
 * @param issuedOn 발급일(한국 날짜)
 * @param expiresOn 이날(한국 날짜)까지 쓸 수 있다
 * @param discountRate 할인율. 5%는 0.05
 */
public record VerifiedPassView(long passId, String passNo, String partnerName, String maskedStudentName,
		LocalDate issuedOn, LocalDate expiresOn, BigDecimal discountRate) {

}
