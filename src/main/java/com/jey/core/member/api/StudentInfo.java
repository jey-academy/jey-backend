package com.jey.core.member.api;

/**
 * 학생. 연락처는 담지 않는다({@link ContactInfo}).
 *
 * @param classId 지금 듣는 반. 정하지 않았으면 {@code null}
 * @param enrolled 재원 중인지. 퇴원한 학생은 {@code false}
 */
public record StudentInfo(long studentId, long campusId, Long classId, String name, boolean enrolled) {
}
