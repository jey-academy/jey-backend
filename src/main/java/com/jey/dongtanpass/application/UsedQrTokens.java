package com.jey.dongtanpass.application;

import java.time.Instant;

/**
 * 데스크나 연계 학원이 확인한 QR 토큰을 기억해 같은 토큰이 두 번 통과하지 않게 한다(ADR-0010). 토큰 문자열이 아니라 토큰 ID로 기억한다.
 * 토큰이 무효가 되는 때(만료 시각)까지만 기억한다.
 */
public interface UsedQrTokens {

	/**
	 * 그 토큰을 쓴 것으로 적는다. 처음이면 {@code true}, 이미 적혀 있으면 {@code false}다.
	 * 동시에 같은 토큰을 적으면 하나만 {@code true}다.
	 *
	 * <p>적지 못하면(저장소에 닿지 못함, 결과를 알 수 없음) 예외를 던진다. 부르는 쪽은 잡지 않는다(그대로 500, 통과시키지 않는다).
	 *
	 * @param now 지금. 확인에 쓴 시각을 그대로 넘긴다(남은 수명을 셀 때 쓴다)
	 */
	boolean markUsed(VerifiedQr qr, Instant now);

}
