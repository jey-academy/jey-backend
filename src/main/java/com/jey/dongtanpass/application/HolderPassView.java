package com.jey.dongtanpass.application;

import com.jey.dongtanpass.domain.PassDirection;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.jey.dongtanpass.domain.PassState;

/**
 * 학부모 화면에 보여 줄 패스. 학생 이름은 가린 것이고 학부모 번호는 없다.
 *
 * @param maskedStudentName 첫 글자와 끝 글자만 남긴 이름
 * @param issuedOn 발급일(한국 날짜)
 * @param expiresOn 이날(한국 날짜)까지 쓸 수 있다
 * @param state 오늘 기준으로 보이는 상태
 * @param daysLeft 오늘을 포함해 쓸 수 있는 날 수. 만료일 당일이면 1, 쓸 수 없는 상태면 0
 * @param discountRate 할인율. 5%는 0.05
 * @param campusName 패스를 받는 지점
 * @param redeemedOn 사용일(한국 날짜). 쓰지 않은 패스는 {@code null}. 취소일은 싣지 않는다
 */
public record HolderPassView(String passNo, PassDirection direction, String partnerName, String maskedStudentName, LocalDate issuedOn,
		LocalDate expiresOn, PassState state, int daysLeft, BigDecimal discountRate, String campusName,
		String noticeVersion, String noticeText, LocalDate redeemedOn) {

}
