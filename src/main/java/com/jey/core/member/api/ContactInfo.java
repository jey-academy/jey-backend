package com.jey.core.member.api;

import com.jey.core.shared.PhoneNumber;

/**
 * 학생의 대표 연락처.
 *
 * <p>{@code phone}에는 원문이 들어 있다. 알림을 보내는 데만 쓴다. 문자열로 바꾸거나 JSON으로 내보내면 가린 표시가 되지만,
 * {@link PhoneNumber#value()}로 꺼낸 값을 화면, 로그, 이벤트에 싣지 않는다.
 *
 * @param name 학부모의 이름. 학생 본인 번호면 학생의 이름
 * @param studentOwn 학부모가 없어서 학생 본인 번호를 쓰는 경우
 */
public record ContactInfo(String name, boolean studentOwn, PhoneNumber phone) {
}
