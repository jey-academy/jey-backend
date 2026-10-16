package com.jey.dongtanpass.application;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.jey.dongtanpass.domain.PassState;

/**
 * 학부모 화면에 보여 줄 패스. 학생 이름은 가린 것이고 학부모 번호는 없다.
 *
 * @param maskedStudentName 첫 글자와 끝 글자만 남긴 이름
 * @param expiresOn 이날(한국 날짜)까지 쓸 수 있다
 * @param state 오늘 기준으로 보이는 상태
 * @param discountRate 할인율. 5%는 0.05
 * @param campusName 패스를 받는 지점
 */
public record HolderPassView(String passNo, String partnerName, String maskedStudentName, LocalDate expiresOn,
		PassState state, BigDecimal discountRate, String campusName, String noticeVersion, String noticeText) {

}
