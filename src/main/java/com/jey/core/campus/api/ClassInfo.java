package com.jey.core.campus.api;

/**
 * 반. 수강료는 담지 않는다({@link ClassFee}).
 *
 * @param active 꺼진 반은 새로 고를 수 없다
 */
public record ClassInfo(long id, long campusId, String name, boolean active) {
}
