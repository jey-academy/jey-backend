package com.jey.dongtanpass.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PartnerTest {

	@Test
	void 새로_만든_연계_학원은_켜져_있고_종류는_학원이다() {
		Partner partner = Partner.create("햇살수학");

		assertThat(partner.getName()).isEqualTo("햇살수학");
		assertThat(partner.getType()).isEqualTo(PartnerType.ACADEMY);
		assertThat(partner.isActive()).isTrue();
	}

	@Test
	void 이름의_앞뒤_공백은_뗀다() {
		assertThat(Partner.create("  햇살수학 ").getName()).isEqualTo("햇살수학");
	}

	@Test
	void 비었거나_너무_긴_이름으로는_만들_수_없다() {
		assertThatIllegalArgumentException().isThrownBy(() -> Partner.create(null));
		assertThatIllegalArgumentException().isThrownBy(() -> Partner.create("   "));
		assertThatIllegalArgumentException().isThrownBy(() -> Partner.create("가".repeat(Partner.MAX_NAME_LENGTH + 1)));
		assertThat(Partner.create("가".repeat(Partner.MAX_NAME_LENGTH)).getName()).hasSize(Partner.MAX_NAME_LENGTH);
	}

}
