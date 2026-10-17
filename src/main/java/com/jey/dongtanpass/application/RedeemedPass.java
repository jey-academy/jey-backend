package com.jey.dongtanpass.application;

import java.time.Instant;

/** 사용 처리한 결과. */
public record RedeemedPass(long redemptionId, long passId, long studentId, Instant redeemedAt) {
}
