package com.jey.dongtanpass.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import com.jey.core.audit.api.AuditEntry;
import com.jey.core.audit.api.AuditLog;
import com.jey.core.auth.api.ClientAttemptLimiter;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.Money;
import com.jey.core.shared.PhoneNumber;
import com.jey.core.shared.PhoneProtector;
import com.jey.dongtanpass.domain.LinkToken;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassNumber;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.PaymentMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 맞은 요청의 횟수를 되돌리는 일은 링크를 바꾼 트랜잭션이 커밋된 뒤에 한다. 되돌리기가 실패해도(저장소 장애)
// 일의 결과는 그대로 나가야 한다. 500이 되면 옛 링크는 이미 막혔는데 새 토큰은 학부모에게 가지 못한다.
// 장치를 일부러 실패시켜야 해서 Spring 없이 만든다(컨텍스트를 늘리지 않는다).
@ExtendWith(OutputCaptureExtension.class)
class HolderRelinkRefundFailureTest {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T03:00:00.123Z"), SEOUL);

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final PhoneNumber PHONE = PhoneNumber.of("01090001234");

	private final PassRepository passes = mock(PassRepository.class);

	private final TransactionTemplate transaction = mock(TransactionTemplate.class);

	private final PhoneProtector phoneProtector = mock(PhoneProtector.class);

	private final ClientAttemptLimiter limiter = mock(ClientAttemptLimiter.class);

	private final AuditLog auditLog = mock(AuditLog.class);

	private HolderRelinkService service;

	private Pass pass;

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		when(transaction.execute(any())).thenAnswer(call -> ((TransactionCallback<Object>) call.getArgument(0))
			.doInTransaction(null));
		when(phoneProtector.fullIndex(PHONE)).thenReturn("phone-index");
		when(limiter.acquire(any(), any(), anyString(), anyString(), any(), any()))
			.thenReturn(mock(ClientAttemptLimiter.Attempt.class));
		doThrow(new IllegalStateException("저장소에 닿지 못했다")).when(limiter).refund(any());
		service = new HolderRelinkService(passes, transaction, phoneProtector, limiter,
				new HolderRelinkProperties(10, 60, Duration.ofMinutes(5)), auditLog, CLOCK);

		pass = Pass.issue(1L, PassNumber.generate(RANDOM), LinkToken.generate(RANDOM).hash(), "김하윤", "phone-index",
				CLOCK.instant(), SEOUL, LocalDate.now(CLOCK), Money.of(150000), PaymentMethod.CARD);
		ReflectionTestUtils.setField(pass, "id", 7L);
		when(passes.findByGuardianPhoneIndex("phone-index")).thenReturn(List.of(pass));
		when(passes.findByIdForUpdate(7L)).thenReturn(Optional.of(pass));
	}

	// 어느 접속 주소가 까닭 없이 막혔다는 문의를 이 로그와 맞춰 볼 수 있게 한도와 결과를 남긴다. 이름과 번호는 남기지 않는다.
	@Test
	void 되돌리기가_실패해도_새_링크를_주고_한도와_결과를_로그에_남긴다(CapturedOutput output) {
		RelinkedLink relinked = service.relink("김하윤", PHONE, "10.0.0.1");

		assertThat(LinkToken.hashOf(relinked.linkToken())).isEqualTo(pass.getLinkTokenHash());
		verify(limiter).refund(any());
		assertThat(output.getAll()).contains("dongtanpass.holder-relink", "result=renewed")
			.doesNotContain("김하윤")
			.doesNotContain("90001234");
	}

	@Test
	void 되돌리기가_실패해도_쓸_수_없는_패스의_거부는_409로_나가고_감사_로그에_남는다(CapturedOutput output) {
		pass.cancel();

		BusinessException denial = catchThrowableOfType(BusinessException.class,
				() -> service.relink("김하윤", PHONE, "10.0.0.1"));

		assertThat(denial).isNotNull();
		assertThat(denial.getErrorCode()).isEqualTo(DongtanpassErrorCode.PASS_CANCELED);
		ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
		verify(auditLog).record(entry.capture());
		assertThat(entry.getValue().action()).isEqualTo(DongtanpassAuditAction.HOLDER_LINK_DENIED);
		assertThat(output.getAll()).contains("result=DONGTANPASS_PASS_CANCELED");
	}

}
