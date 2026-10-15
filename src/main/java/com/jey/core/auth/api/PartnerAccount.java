package com.jey.core.auth.api;

import java.time.Instant;

/**
 * 만든 연계 학원 계정. 비밀번호와 그 해시는 싣지 않는다.
 *
 * @param loginId 저장한 아이디(소문자로 맞춘 것)
 */
public record PartnerAccount(long id, String loginId, String name, long partnerId, Instant createdAt) {

}
