package com.jey.core.member;

import com.jey.core.member.domain.GuardianRelation;
import com.jey.core.shared.PhoneNumber;

/**
 * 학생에게 붙일 학부모. 같은 번호의 학부모가 이미 있으면 새로 만들지 않고 그 학부모를 붙인다.
 * 그때 {@code name}은 쓰이지 않는다(저장된 이름을 바꾸지 않는다).
 */
public record GuardianInput(String name, GuardianRelation relation, PhoneNumber phone) {
}
