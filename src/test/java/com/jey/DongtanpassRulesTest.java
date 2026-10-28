package com.jey;

import com.jey.dongtanpass.domain.PartnerDiscount;
import org.junit.jupiter.api.Test;

// 제이G패스 모듈의 값 타입 중 팩터리로만 만드는 것.
class DongtanpassRulesTest {

	// 생성자로는 원래 수강료·할인액·받을 금액이 서로 맞지 않는 값을 만들 수 있다. PartnerDiscount.of가 계산해서 만든다.
	@Test
	void 연계_학원_할인_금액은_계산해서만_만든다() {
		ConstructorRules.constructedOnlyInside(PartnerDiscount.class).check(MainClasses.get());
	}

}
