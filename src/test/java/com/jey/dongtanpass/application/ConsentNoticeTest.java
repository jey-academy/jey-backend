package com.jey.dongtanpass.application;

import com.jey.dongtanpass.domain.PassDirection;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConsentNoticeTest {

	// 버전은 동의 기록 칸(20자)에 들어가야 한다.
	@Test
	void 지금_버전은_비어_있지_않고_20자_안이다() {
		assertThat(ConsentNotice.CURRENT.version()).isNotBlank().hasSizeLessThanOrEqualTo(ConsentNotice.MAX_VERSION_LENGTH);
		assertThat(ConsentNotice.MAX_VERSION_LENGTH).isEqualTo(20);
	}

	@Test
	void 문구가_비어_있지_않다() {
		assertThat(ConsentNotice.CURRENT.text()).isNotBlank();
	}

	@Test
	void 지금_버전인지_가린다() {
		assertThat(ConsentNotice.isCurrent(ConsentNotice.CURRENT.version())).isTrue();
		assertThat(ConsentNotice.isCurrent("2000-01-v0")).isFalse();
		assertThat(ConsentNotice.isCurrent(null)).isFalse();
	}

	// 연계 학원이 발급할 때와 JEY가 발급할 때는 받는 쪽이 달라 문구도 다르다. 동의 기록에는 버전만 남으므로 버전도 달라야 한다.
	@Test
	void 방향마다_문구와_버전이_다르다() {
		assertThat(ConsentNotice.of(PassDirection.PARTNER_ISSUED)).isEqualTo(ConsentNotice.CURRENT);
		assertThat(ConsentNotice.of(PassDirection.CAMPUS_ISSUED)).isEqualTo(ConsentNotice.CAMPUS_ISSUED);
		assertThat(ConsentNotice.CAMPUS_ISSUED.version()).isNotEqualTo(ConsentNotice.CURRENT.version())
			.hasSizeLessThanOrEqualTo(ConsentNotice.MAX_VERSION_LENGTH);
		assertThat(ConsentNotice.CAMPUS_ISSUED.text()).isNotEqualTo(ConsentNotice.CURRENT.text()).contains("연계 학원");
	}

	@Test
	void 다른_방향의_버전은_지금_버전이_아니다() {
		assertThat(ConsentNotice.isCurrent(PassDirection.CAMPUS_ISSUED, ConsentNotice.CAMPUS_ISSUED.version())).isTrue();
		assertThat(ConsentNotice.isCurrent(PassDirection.CAMPUS_ISSUED, ConsentNotice.CURRENT.version())).isFalse();
		assertThat(ConsentNotice.isCurrent(PassDirection.PARTNER_ISSUED, ConsentNotice.CAMPUS_ISSUED.version())).isFalse();
		assertThat(ConsentNotice.isCurrent(PassDirection.CAMPUS_ISSUED, null)).isFalse();
	}

}
