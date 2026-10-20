package com.jey;

import com.jey.dongtanpass.domain.PartnerDiscount;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

// 동탄고 패스 모듈의 값 타입 중 팩터리로만 만드는 것.
class DongtanpassRulesTest {

	// 생성자로는 원래 수강료·할인액·받을 금액이 서로 맞지 않는 값을 만들 수 있다. PartnerDiscount.of가 계산해서 만든다.
	@Test
	void 연계_학원_할인_금액은_계산해서만_만든다() {
		// 읽은 클래스는 static 필드에 두지 않는다(테스트가 끝나도 남아 테스트 JVM의 힙을 차지한다).
		JavaClasses main = new ClassFileImporter()
			.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
			.importPackages("com.jey");
		ConstructorRules.constructedOnlyInside(PartnerDiscount.class).check(main);
	}

}
