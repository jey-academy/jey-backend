package com.jey.dongtanpass.application;

import java.time.Instant;

import com.jey.dongtanpass.domain.PartnerDiscount;

/**
 * 연계 학원이 쓴 JEY 발급 패스와 그때 계산한 금액.
 *
 * @param redemptionId 연계 학원 사용 기록의 ID
 */
public record PartnerRedeemedPass(long redemptionId, long passId, PartnerDiscount discount, Instant redeemedAt) {
}
