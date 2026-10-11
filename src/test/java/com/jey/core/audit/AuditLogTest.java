package com.jey.core.audit;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.audit.api.AuditLog;
import com.jey.core.audit.api.AuditTarget;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

// 테스트끼리 DB를 같이 쓴다. 행위 이름을 매번 새로 만들어 그 이름으로 찾는다.
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
	Clock clock;

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1, id = ACTOR)
	void 누가_언제_무엇에_무엇을_했는지_남긴다() {
		String action = newAction();
		Instant before = clock.instant().truncatedTo(ChronoUnit.MICROS);
		Map<String, Object> detail = new LinkedHashMap<>();
		detail.put("phoneChanged", true);
		detail.put("guardianId", 17L);
		detail.put("role", UserRole.STAFF);
		detail.put("note", "관계 변경");

		auditLog.record(action, AuditTarget.of("student", 42), detail);

		AuditRecord saved = only(action);
		assertThat(saved.getActorId()).isEqualTo(ACTOR);
		assertThat(saved.getAction()).isEqualTo(action);
		assertThat(saved.getTargetType()).isEqualTo("student");
		assertThat(saved.getTargetId()).isEqualTo(42L);
		assertThat(saved.getOccurredAt()).isBetween(before, clock.instant());
		assertThat(saved.getEventId()).isNull();
		assertThat(saved.getCampusId()).isNull();
		// 키 순서를 고정한다. enum은 이름으로 싣는다.
		assertThat(saved.getDetailJson())
				.isEqualTo("{\"guardianId\":17,\"note\":\"관계 변경\",\"phoneChanged\":true,\"role\":\"STAFF\"}");
	}

	// 배치나 이벤트 리스너처럼 로그인 없이 도는 코드에서 부른 경우다.
	@Test
	void 로그인이_없으면_행위자가_비어_있다() {
		String action = newAction();

		auditLog.record(action, null, Map.of());
		auditLog.recordAttempt(action, null, null);

		assertThat(find(action)).hasSize(2).allSatisfy(each -> {
			assertThat(each.getActorId()).isNull();
			assertThat(each.getTargetType()).isNull();
			assertThat(each.getTargetId()).isNull();
			assertThat(each.getDetailJson()).isNull();
		});
	}

	// "했다"는 기록은 그 일이 실제로 커밋됐을 때만 남아야 한다.
	@Test
	@WithJeyUser(role = UserRole.ADMIN, id = ACTOR)
	void 성공한_일의_기록은_그_일이_롤백되면_같이_사라진다() {
		String action = newAction();

		transaction.executeWithoutResult(status -> {
			auditLog.record(action, AuditTarget.of("student", 1), Map.of());
			status.setRollbackOnly();
		});

		assertThat(find(action)).isEmpty();
	}

	// 거부된 요청은 예외로 끝나 롤백된다. 가장 남겨야 할 기록이 같이 사라지면 안 된다.
	@Test
	@WithJeyUser(role = UserRole.ADMIN, id = ACTOR)
	void 거부된_시도의_기록은_그_요청이_롤백돼도_남는다() {
		String action = newAction();

		assertThatIllegalStateException().isThrownBy(() -> transaction.executeWithoutResult(status -> {
			auditLog.recordAttempt(action, AuditTarget.of("guardian", 9), Map.of("guardianId", 9));
			throw new IllegalStateException("거부됨");
		}));

		assertThat(only(action).getActorId()).isEqualTo(ACTOR);
		assertThat(only(action).getTargetId()).isEqualTo(9L);
	}

	// 기록은 오래 남고 여러 사람이 본다. 전화번호가 한 번 들어가면 지울 방법이 없다.
	@ParameterizedTest
	@ValueSource(strings = { "010-1234-5678", "01012345678", "010 1234 5678", "+82 10-1234-5678", "011-234-5678",
			"연락처 010.1234.5678 로 변경" })
	void 상세에_전화번호처럼_보이는_문자열이_있으면_기록하지_않는다(String value) {
		String action = newAction();

		assertThatIllegalArgumentException().isThrownBy(() -> auditLog.record(action, null, Map.of("memo", value)))
				.withMessageContaining("memo")
				.withMessageNotContaining("1234");
		assertThatIllegalArgumentException().isThrownBy(() -> auditLog.recordAttempt(action, null, Map.of("memo", value)));

		assertThat(find(action)).isEmpty();
	}

	// ID는 숫자로 싣는다. 우연히 전화번호 모양인 숫자 ID까지 막으면 정상 기록이 실패한다.
	@Test
	void 숫자로_실은_값과_전화번호가_아닌_문자열은_통과한다() {
		String action = newAction();

		auditLog.record(action, null, Map.of("paymentId", 1012345678L, "code", "SIBLING_5", "month", "2026-11"));

		assertThat(only(action).getDetailJson())
				.isEqualTo("{\"code\":\"SIBLING_5\",\"month\":\"2026-11\",\"paymentId\":1012345678}");
	}

	@Test
	void 상세에는_숫자와_참거짓과_enum과_짧은_문자열만_넣을_수_있다() {
		String action = newAction();

		assertThatIllegalArgumentException().isThrownBy(() -> auditLog.record(action, null, Map.of("ids", List.of(1))));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> auditLog.record(action, null, Map.of("nested", Map.of("a", 1))));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> auditLog.record(action, null, Map.of("memo", "가".repeat(201))));
		Map<String, Object> withNull = new LinkedHashMap<>();
		withNull.put("missing", null);
		assertThatIllegalArgumentException().isThrownBy(() -> auditLog.record(action, null, withNull));

		assertThat(find(action)).isEmpty();
	}

	@Test
	void 상세가_너무_크면_기록하지_않는다() {
		String action = newAction();
		Map<String, Object> large = new LinkedHashMap<>();
		for (int i = 0; i < 12; i++) {
			large.put("key" + i, "가".repeat(200));
		}

		assertThatIllegalArgumentException().isThrownBy(() -> auditLog.record(action, null, large));

		assertThat(find(action)).isEmpty();
	}

	// 행위 이름은 조회 조건이다. 형식이 흐트러지면 조회에서 조용히 빠진다.
	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "", "student-updated", "Member.student-updated", "member.student_updated",
			"member.student updated", "member." })
	void 행위_이름은_모듈과_사건을_점으로_이은_소문자여야_한다(String action) {
		assertThatIllegalArgumentException().isThrownBy(() -> auditLog.record(action, null, Map.of()));
		assertThatIllegalArgumentException().isThrownBy(() -> auditLog.recordAttempt(action, null, Map.of()));
	}

	@Test
	void 행위_이름과_대상_종류는_정해진_길이를_넘을_수_없다() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> auditLog.record("member." + "a".repeat(80), null, Map.of()));
		assertThatIllegalArgumentException().isThrownBy(() -> AuditTarget.of("a".repeat(41), 1));
		assertThatIllegalArgumentException().isThrownBy(() -> AuditTarget.of("Student", 1));
		assertThatIllegalArgumentException().isThrownBy(() -> AuditTarget.of(null, 1));
		assertThatIllegalArgumentException().isThrownBy(() -> AuditTarget.of("student", 0));
		assertThat(AuditTarget.of("student-guardian", 1).type()).isEqualTo("student-guardian");
	}

	// 기록은 추가만 한다. 값을 바꾸는 메서드가 생기면 여기서 걸린다.
	@Test
	void 기록에는_값을_바꾸는_메서드가_없다() {
		List<String> publicInstanceMethods = Arrays.stream(AuditRecord.class.getDeclaredMethods())
				.filter(method -> Modifier.isPublic(method.getModifiers()) && !Modifier.isStatic(method.getModifiers()))
				.map(Method::getName)
				.toList();

		assertThat(publicInstanceMethods).isNotEmpty().allMatch(name -> name.startsWith("get"));
	}

	private AuditRecord only(String action) {
		List<AuditRecord> found = find(action);
		assertThat(found).hasSize(1);
		return found.getFirst();
	}

	private List<AuditRecord> find(String action) {
		return auditLogService.search(null, action, null, null, null, null, 0, 100).getContent();
	}

	static String newAction() {
		return "audittest.case-" + UUID.randomUUID().toString().substring(0, 8);
	}

}
