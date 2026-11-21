package com.jey.dongtanpass.domain;

import java.time.Instant;

/**
 * 연계 학원 장부 CSV의 "그 학원이 발급한 패스가 JEY에서 쓰인 기록" 한 줄(조회 결과). 패스 번호·학생 이름(패스의 이름)과 쓴 지점을 붙인다.
 */
public record UsedAtJeyLine(long passId, Instant redeemedAt, String studentName, String passNo, long campusId) {

	// 학생 이름이 로그에 찍히지 않게 한다.
	@Override
	public String toString() {
		return "UsedAtJeyLine[passId=" + passId + "]";
	}

}
