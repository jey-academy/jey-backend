package com.jey.dongtanpass.domain;

/** 저장하는 상태. 만료는 저장하지 않는다(만료일과 오늘로 계산한다 — {@link PassState}). */
public enum PassStatus {

	ISSUED, REDEEMED, CANCELED

}
