package com.jey.dongtanpass.application;

import java.time.Duration;

/**
 * 연계 학원이 방금 확인(QR·패스 번호)한 패스. 본인 확인, 금액 미리 보기, 사용 처리는 그 학원이 확인한 패스에만 된다.
 * 연계 학원은 이 시스템 밖의 조직이라, 확인을 거치지 않은 패스 ID로 다음 단계를 불러 패스의 있음·상태·주인을 알아내지 못하게 한다.
 * 확인은 학원 단위다(학원의 공용 계정이 여럿이어도 같다).
 *
 * <p>적거나 읽지 못하면(저장소에 닿지 못함) 예외를 던진다. 부르는 쪽은 잡지 않는다(그대로 500, 통과시키지 않는다).
 */
public interface PartnerVerifiedPasses {

	/** 확인한 뒤 다음 단계를 마칠 시간. 확인 → 본인 확인 → 수강료 입력 → 사용 처리를 한 자리에서 한다. */
	Duration TTL = Duration.ofMinutes(30);

	/** 이 학원이 이 패스를 확인했다고 적는다(이미 있으면 시간을 새로 센다). */
	void remember(long partnerId, long passId);

	/** 이 학원이 이 패스를 {@link #TTL} 안에 확인했는지. */
	boolean isVerified(long partnerId, long passId);

}
