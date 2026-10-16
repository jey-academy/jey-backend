package com.jey.core.campus.api;

/**
 * 지점. 조회한 시점의 값이다.
 *
 * @param name 화면에 보여 줄 지점 이름
 */
public record CampusInfo(long id, String name) {
}
