package com.jey;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.Date;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 현재 시각은 주입받은 Clock에서만 얻는다. 직접 now()를 부르면 테스트에서 시각을 고정할 수 없고,
// 서버의 기본 시간대에 따라 "오늘"이 달라진다.
class TimeRulesTest {

	private static final ArchRule NO_DIRECT_NOW = noClasses().should()
			.callMethod(Instant.class, "now")
			.orShould().callMethod(LocalDate.class, "now")
			.orShould().callMethod(LocalDateTime.class, "now")
			.orShould().callMethod(LocalTime.class, "now")
			.orShould().callMethod(OffsetDateTime.class, "now")
			.orShould().callMethod(ZonedDateTime.class, "now")
			.orShould().callMethod(YearMonth.class, "now")
			.orShould().callMethod(System.class, "currentTimeMillis")
			.orShould().callMethod(Clock.class, "systemDefaultZone")
			.orShould().callMethod(Clock.class, "systemUTC")
			.orShould().callConstructor(Date.class)
			.because("현재 시각은 주입받은 Clock에서 얻어야 한다 (예: LocalDate.now(clock), clock.instant())");

	@Test
	void 운영_코드는_현재_시각을_직접_구하지_않는다() {
		var mainClasses = new ClassFileImporter()
				.withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
				.importPackages("com.jey");

		NO_DIRECT_NOW.check(mainClasses);
	}

	// 규칙이 실제로 위반을 잡는지 확인한다.
	@Test
	void 직접_now를_부르면_규칙에_걸린다() {
		var violating = new ClassFileImporter().importClasses(CallsNowDirectly.class);

		assertThatThrownBy(() -> NO_DIRECT_NOW.check(violating))
				.isInstanceOf(AssertionError.class)
				.hasMessageContaining("LocalDate.now()");
	}

	@Test
	void 시계를_넘겨_구하는_것은_허용한다() {
		var allowed = new ClassFileImporter().importClasses(UsesClock.class);

		NO_DIRECT_NOW.check(allowed);
	}

	static class CallsNowDirectly {

		LocalDate today() {
			return LocalDate.now();
		}

	}

	static class UsesClock {

		LocalDate today(Clock clock) {
			return LocalDate.now(clock);
		}

		Instant now(Clock clock) {
			return clock.instant();
		}

	}

}
