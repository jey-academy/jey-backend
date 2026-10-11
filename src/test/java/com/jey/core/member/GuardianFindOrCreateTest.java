package com.jey.core.member;

import java.util.Optional;

import com.jey.TestcontainersConfiguration;
import com.jey.core.audit.api.AuditLog;
import com.jey.core.member.GuardianService.EnsuredGuardian;
import com.jey.core.member.domain.Guardian;
import com.jey.core.member.domain.GuardianRepository;
import com.jey.core.shared.PhoneNumber;
import com.jey.core.shared.PhoneProtector;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import static com.jey.core.member.MemberFixtures.randomPhone;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

// 동시에 등록하는 테스트는 스레드가 실제로 겹쳐야만 "유니크 키에 막힌 쪽"을 지난다. 겹치지 않아도 통과한다.
// 여기서는 첫 조회만 "없음"으로 답하게 해서 그 길을 매번 지나게 한다. 나머지는 실제 DB다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class GuardianFindOrCreateTest {

	@Autowired
	GuardianRepository guardians;

	@Autowired
	PhoneProtector protector;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	AuditLog auditLog;

	@Test
	void 확인과_저장_사이에_같은_번호가_먼저_들어오면_먼저_만들어진_학부모를_돌려준다() {
		PhoneNumber phone = randomPhone();
		Long winner = guardians.saveAndFlush(Guardian.create("먼저 등록된 이름", protector.protect(phone))).getId();
		GuardianRepository firstLookupMisses = mock(GuardianRepository.class, delegatesTo(guardians));
		doReturn(Optional.empty()).doAnswer(call -> guardians.findByPhoneIndex(call.getArgument(0)))
				.when(firstLookupMisses)
				.findByPhoneIndex(any());
		GuardianService service = new GuardianService(firstLookupMisses, protector, transactionManager, auditLog);

		EnsuredGuardian ensured = service.findOrCreate("나중에 온 이름", phone);

		assertThat(ensured).isEqualTo(new EnsuredGuardian(winner, true));
		assertThat(guardians.findById(winner)).map(Guardian::getName).contains("먼저 등록된 이름");
	}

	// 유니크 키가 아닌 저장 실패(길이 초과, NOT NULL 등)를 "이미 있는 학부모"로 바꾸면 원인이 가려진다.
	@Test
	void 유니크_키가_아닌_저장_실패는_그대로_드러낸다() {
		GuardianRepository saveFails = mock(GuardianRepository.class, delegatesTo(guardians));
		DataIntegrityViolationException failure = new DataIntegrityViolationException("다른 제약 위반");
		doThrow(failure).when(saveFails).saveAndFlush(any());
		GuardianService service = new GuardianService(saveFails, protector, transactionManager, auditLog);

		assertThatThrownBy(() -> service.findOrCreate("김어머니", randomPhone())).isSameAs(failure);
	}

}
