package com.jey.dongtanpass.application;

import java.time.Instant;

import com.jey.dongtanpass.domain.Partner;
import com.jey.dongtanpass.domain.PartnerType;

/** 연계 학원 하나의 내용. */
public record PartnerView(long id, String name, PartnerType type, boolean active, Instant createdAt) {

	static PartnerView from(Partner partner) {
		return new PartnerView(partner.getId(), partner.getName(), partner.getType(), partner.isActive(),
				partner.getCreatedAt());
	}

}
