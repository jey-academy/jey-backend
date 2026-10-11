package com.jey.core.audit;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.audit.api.AuditEntry;
import com.jey.core.audit.api.AuditLog;
import com.jey.core.audit.api.AuditOutcome;
import com.jey.core.audit.domain.AuditRecord;
import com.jey.core.auth.api.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class AuditLogTest {

	private static final long ACTOR = 4321;

	@Autowired
	AuditLog auditLog;

	@Autowired
	AuditLogService auditLogService;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	JsonMapper jsonMapper;

	@Autowired
	Clock clock;

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1, id = ACTOR)
	void 누가_언제_어느_지점의_무엇에_무엇을_했는지_남긴다() {
		TestAuditAction action = TestAuditAction.done();
		Instant before = clock.instant().truncatedTo(ChronoUnit.MICROS);

		transaction.executeWithoutResult(status -> auditLog.record(AuditEntry.of(action)
				.target("student", 42)
				.campus(2)
				.with("phoneChanged", true)
				.with("guardianId", 17L)
				.with("role", UserRole.STAFF)
				.with("note", "관계 변경")));

		AuditRecord saved = only(action);
		assertThat(saved.getActorId()).isEqualTo(ACTOR);
		assertThat(saved.getAction()).isEqualTo(action.code());
		assertThat(saved.getOutcome()).isEqualTo(AuditOutcome.DONE);
		assertThat(saved.getTargetType()).isEqualTo("student");
		assertThat(saved.getTargetId()).isEqualTo(42L);
		assertThat(saved.getCampusId()).isEqualTo(2L);
		assertThat(saved.getOccurredAt()).isBetween(before, clock.instant());
		assertThat(saved.getEventId()).isNull();
		// 키 순서를 고정한다. enum은 이름으로 싣는다.
		assertThat(saved.getDetailJson())
				.isEqualTo("{\"guardianId\":17,\"note\":\"관계 변경\",\"phoneChanged\":true,\"role\":\"STAFF\"}");
	}

	// 배치나 이벤트 리스너처럼 로그인 없이 도는 코드에서 부른 경우다.
	@Test
	void 로그인이_없으면_행위자가_비어_있고_대상과_지점과_상세는_없어도_된다() {
		TestAuditAction done = TestAuditAction.done();
		TestAuditAction denied = TestAuditAction.denied();

		transaction.executeWithoutResult(status -> auditLog.record(AuditEntry.of(done)));
		auditLog.record(AuditEntry.of(denied));

		assertThat(List.of(only(done), only(denied))).allSatisfy(each -> {
			assertThat(each.getActorId()).isNull();
			assertThat(each.getTargetType()).isNull();
			assertThat(each.getTargetId()).isNull();
			assertThat(each.getCampusId()).isNull();
			assertThat(each.getDetailJson()).isNull();
		});
		assertThat(only(denied).getOutcome()).isEqualTo(AuditOutcome.DENIED);
	}

	// "했다"는 기록은 그 일이 실제로 커밋됐을 때만 남아야 한다.
	@Test
	@WithJeyUser(role = UserRole.ADMIN, id = ACTOR)
	void 한_일의_기록은_그_일이_롤백되면_같이_사라진다() {
		TestAuditAction action = TestAuditAction.done();

		transaction.executeWithoutResult(status -> {
			auditLog.record(AuditEntry.of(action).target("student", 1));
			status.setRollbackOnly();
		});

		assertThat(find(action)).isEmpty();
	}

	// 트랜잭션 밖에서 적으면 그 일이 롤백돼도 "했다"만 남는다. 그렇게 부르는 코드는 바로 드러나야 한다.
	@Test
	void 한_일의_기록을_트랜잭션_밖에서_남기려_하면_멈춘다() {
		TestAuditAction action = TestAuditAction.done();

		assertThatIllegalStateException().isThrownBy(() -> auditLog.record(AuditEntry.of(action)))
				.withMessageContaining(action.code());

		assertThat(find(action)).isEmpty();
	}

	// 거부된 요청은 예외로 끝나 롤백된다. 그 트랜잭션이 끝난 뒤에 따로 적으므로 남는다.
	@Test
	@WithJeyUser(role = UserRole.ADMIN, id = ACTOR)
	void 거부된_시도의_기록은_그_요청이_롤백된_뒤에_남긴다() {
		TestAuditAction action = TestAuditAction.denied();

		try {
			transaction.executeWithoutResult(status -> {
				throw new IllegalStateException("거부됨");
			});
		}
		catch (IllegalStateException denied) {
			auditLog.record(AuditEntry.of(action).target("guardian", 9));
		}

		assertThat(only(action).getActorId()).isEqualTo(ACTOR);
		assertThat(only(action).getTargetId()).isEqualTo(9L);
		assertThat(only(action).getOutcome()).isEqualTo(AuditOutcome.DENIED);
	}

	// 트랜잭션 안에서 따로 커밋하려면 DB 연결이 하나 더 필요하다. 거부될 요청이 몰리면 연결 풀이 바닥난다.
	@Test
	void 거부된_시도의_기록을_트랜잭션_안에서_남기려_하면_멈춘다() {
		TestAuditAction action = TestAuditAction.denied();

		assertThatIllegalStateException()
				.isThrownBy(() -> transaction.executeWithoutResult(status -> auditLog.record(AuditEntry.of(action))))
				.withMessageContaining(action.code());

		assertThat(find(action)).isEmpty();
	}

	// 기록은 오래 남고 여러 사람이 본다. 전화번호가 한 번 들어가면 지울 방법이 없다.
	@ParameterizedTest
	@ValueSource(strings = { "010-1234-5678", "01012345678", "010 1234 5678", "+82 10-1234-5678", "011-234-5678",
			"연락처 010.1234.5678 로 변경", "010/1234/5678", "010_1234_5678", "(010)1234-5678", "010)1234-5678",
			"+82 (10) 1234-5678", "０１０－１２３４－５６７８", "010·1234·5678", "010-****-5678", "011-***-5678" })
	void 전화번호처럼_보이는_문자열은_싣지_못한다(String value) {
		TestAuditAction action = TestAuditAction.denied();

		assertThatIllegalArgumentException()
				.isThrownBy(() -> auditLog.record(AuditEntry.of(action).with("memo", value)))
				.withMessageContaining("memo")
				.withMessageNotContaining("1234")
				.withMessageNotContaining("5678");

		assertThat(find(action)).isEmpty();
	}

	// 식별자 안의 숫자 구간을 전화번호로 보면 정상 기록이 가끔 실패한다. 결제 키나 멱등 키가 이런 모양이다.
	@ParameterizedTest
	@ValueSource(strings = { "SIBLING_5", "2026-11", "2026-10-11", "2026-10-11T01:02:03.012345Z", "ORD-2026-1011-0001",
			"a01012345678b", "pay_01012345678", "20261101123456", "0101234567890", "3fa85f64-0101-2345-6780-10123456789a",
			"0192f8a4-0101-7234-8567-801012345678" })
	void 전화번호가_아닌_문자열은_실린다(String value) {
		assertThat(AuditLogService.looksLikePhone(value)).isFalse();
	}

	@Test
	void 무작위_UUID는_전화번호로_보이지_않는다() {
		for (int i = 0; i < 20_000; i++) {
			String uuid = UUID.randomUUID().toString();
			assertThat(AuditLogService.looksLikePhone(uuid)).as(uuid).isFalse();
		}
	}

	// ID는 숫자로 싣는다. 숫자는 전화번호 모양이어도 검사하지 않는다.
	@Test
	void 숫자로_실은_값은_전화번호_모양이어도_실린다() {
		TestAuditAction action = TestAuditAction.denied();

		auditLog.record(AuditEntry.of(action).with("paymentId", 1012345678L));

		assertThat(only(action).getDetailJson()).isEqualTo("{\"paymentId\":1012345678}");
	}

	@Test
	void 싣는_값의_이름과_값은_비울_수_없고_문자열은_200자까지다() {
		AuditEntry entry = AuditEntry.of(TestAuditAction.denied());

		assertThatIllegalArgumentException().isThrownBy(() -> entry.with(" ", 1));
		assertThatIllegalArgumentException().isThrownBy(() -> entry.with(null, true));
		assertThatNullPointerException().isThrownBy(() -> entry.with("memo", (String) null));
		assertThatNullPointerException().isThrownBy(() -> entry.with("role", (UserRole) null));
		assertThatIllegalArgumentException().isThrownBy(() -> entry.with("memo", "가".repeat(201)));
		assertThatIllegalArgumentException().isThrownBy(() -> entry.campus(0));
		assertThatIllegalArgumentException().isThrownBy(() -> entry.target("Student", 1));
		assertThatIllegalArgumentException().isThrownBy(() -> entry.target("student", 0));
		assertThatNullPointerException().isThrownBy(() -> AuditEntry.of(null));
		assertThat(entry.with("memo", "가".repeat(200)).detail()).containsKey("memo");
	}

	@Test
	void 상세는_정확히_컬럼_크기까지_되고_한_글자_넘으면_기록하지_않는다() {
		TestAuditAction fits = TestAuditAction.denied();
		TestAuditAction over = TestAuditAction.denied();
		AuditEntry base = AuditEntry.of(fits);
		for (int i = 0; i < 9; i++) {
			base.with("k" + i, "a".repeat(200));
		}
		int withoutLast = jsonMapper.writeValueAsString(base.with("last", "").detail()).length();
		String last = "a".repeat(AuditRecord.MAX_DETAIL_LENGTH - withoutLast);

		auditLog.record(base.with("last", last));
		AuditEntry tooLarge = AuditEntry.of(over);
		base.detail().forEach((key, value) -> tooLarge.with(key, (String) value));
		assertThatIllegalArgumentException().isThrownBy(() -> auditLog.record(tooLarge.with("last", last + "a")));

		assertThat(only(fits).getDetailJson()).hasSize(AuditRecord.MAX_DETAIL_LENGTH);
		assertThat(find(over)).isEmpty();
	}

	// 행위 이름은 조회 조건이다. 형식이 흐트러지면 조회에서 조용히 빠진다.
	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "", "student-updated", "Member.student-updated", "member.student_updated",
			"member.student updated", "member." })
	void 행위_이름은_모듈과_사건을_점으로_이은_소문자여야_한다(String code) {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> auditLog.record(AuditEntry.of(new TestAuditAction(code, AuditOutcome.DENIED))));
	}

	@Test
	void 행위_이름은_80자까지이고_결과가_정해져_있어야_한다() {
		String longest = "audittest." + "a".repeat(70);

		auditLog.record(AuditEntry.of(new TestAuditAction(longest, AuditOutcome.DENIED)));

		assertThat(longest).hasSize(80);
		assertThat(auditLogService.search(null, longest, null, null, null, null, null, 0, 10)).hasSize(1);
		assertThatIllegalArgumentException()
				.isThrownBy(() -> auditLog.record(AuditEntry.of(new TestAuditAction(longest + "a", AuditOutcome.DENIED))));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> auditLog.record(AuditEntry.of(new TestAuditAction(TestAuditAction.newCode(), null))));
	}

	// 기록은 추가만 한다. 값을 바꾸는 메서드가 생기면 여기서 걸린다.
	@Test
	void 기록에는_값을_바꾸는_메서드가_없다() {
		List<String> instanceMethods = Arrays.stream(AuditRecord.class.getDeclaredMethods())
				.filter(method -> !Modifier.isStatic(method.getModifiers()) && !method.isSynthetic())
				.map(Method::getName)
				.toList();

		assertThat(instanceMethods).isNotEmpty().allMatch(name -> name.matches("get[A-Z]\\w*"));
		assertThat(Arrays.stream(AuditRecord.class.getDeclaredMethods())
				.filter(method -> !Modifier.isStatic(method.getModifiers()))
				.allMatch(method -> method.getParameterCount() == 0)).isTrue();
	}

	private AuditRecord only(TestAuditAction action) {
		List<AuditRecord> found = find(action);
		assertThat(found).hasSize(1);
		return found.getFirst();
	}

	private List<AuditRecord> find(TestAuditAction action) {
		return auditLogService.search(null, action.code(), null, null, null, null, null, 0, 100).getContent();
	}

}
