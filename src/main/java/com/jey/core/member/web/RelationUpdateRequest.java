package com.jey.core.member.web;

import com.jey.core.member.domain.GuardianRelation;
import jakarta.validation.constraints.NotNull;

record RelationUpdateRequest(@NotNull GuardianRelation relation) {
}
