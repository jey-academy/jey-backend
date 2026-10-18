package com.jey.core.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;

import com.jey.core.shared.PhoneNumber;
import org.junit.jupiter.api.Test;

class MemberFixturesTest {

	@Test
	void 뒷자리가_같고_서로_다른_번호를_만든다() {
		List<PhoneNumber> phones = MemberFixtures.distinctPhonesEndingWith("1234", 10_000);

		assertThat(phones).hasSize(10_000).doesNotHaveDuplicates();
		assertThat(phones).allSatisfy(phone -> assertThat(phone.last4()).isEqualTo("1234"));
	}

	@Test
	void 만들_수_없는_개수는_거부한다() {
		assertThatIllegalArgumentException().isThrownBy(() -> MemberFixtures.distinctPhonesEndingWith("1234", 0));
		assertThatIllegalArgumentException().isThrownBy(() -> MemberFixtures.distinctPhonesEndingWith("1234", 10_001));
	}
}
