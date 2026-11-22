package com.jey.core.member;

import static com.jey.core.member.MemberFixtures.mother;
import static com.jey.core.member.MemberFixtures.randomPhone;
import static com.jey.core.member.MemberFixtures.registration;
import static com.jey.core.member.MemberFixtures.uniqueName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;

import com.jey.TestcontainersConfiguration;
import com.jey.core.member.GuardianService.GuardianProfile;
import com.jey.core.member.StudentView.GuardianView;
import com.jey.core.member.domain.Guardian;
import com.jey.core.member.domain.GuardianRepository;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.PhoneNumber;
import com.jey.core.shared.PhoneProtector;

// 두 학부모 수정이 서로 끼어드는 순서를 고정해 재현한다. 무작위로 동시에 보내면 번호를 바꾸는 쪽이 늘 늦게 끝나
// 틈을 거의 맞추지 못한다(20회 중 0회). 그래서 "다른 수정이 행을 바꾸고 커밋하기 전"에 학부모 수정이 들어오게 하고,
// 그 요청이 실제로 잠금을 기다리기 시작한 것을 DB에서 확인한 뒤에 커밋한다(시간으로 맞추지 않는다).
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class GuardianUpdateInterleavingTest {

	@Autowired
	StudentService studentService;

	@Autowired
	GuardianService guardianService;

	@Autowired
	GuardianRepository guardians;

	@Autowired
	PhoneProtector protector;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	MySQLContainer mysql;

	// 다른 수정(T)이 번호를 바꿔 행을 쥐고 있는 동안 이름만 고치는 요청(S)이 들어온다. S는 번호를 건드리지 않았으므로,
	// T가 커밋한 뒤에도 번호는 T가 바꾼 값이어야 한다. 잠그지 않고 읽으면 S는 T 이전의 번호를 읽고, 모든 칸을 다시 써서
	// T의 번호를 옛 값으로 되돌린다.
	// S의 화면(버전 0)은 T가 커밋한 순간 낡은 화면이 되므로 S는 409로 거부된다. 잠금이 없으면 S는 T가 커밋하기 전의 버전을
	// 읽어 비교를 통과하고, 저장할 때 Hibernate의 버전 확인에 걸려 처리하지 못한 예외(500)로 끝난다.
	@Test
	void 다른_수정이_번호를_바꾸는_동안_들어온_이름_수정은_그_번호를_되돌리지_않고_낡은_화면으로_거부된다() throws Exception {
		StudentView student = studentService.register(registration(uniqueName(), mother(randomPhone())));
		long guardianId = student.guardians().getFirst().id();
		PhoneNumber changedByOther = randomPhone();
		long waitsBefore = lockWaits();

		CompletableFuture<GuardianProfile> renamed = new CompletableFuture<>();
		transaction.executeWithoutResult(status -> {
			Guardian held = guardians.findById(guardianId).orElseThrow();
			held.changePhone(protector.protect(changedByOther));
			guardians.saveAndFlush(held); // 커밋 전까지 행의 배타 잠금을 쥔다

			Thread.ofPlatform().start(() -> {
				try {
					renamed.complete(guardianService.update(guardianId, 0, "새이름", null));
				}
				catch (RuntimeException ex) {
					renamed.completeExceptionally(ex);
				}
			});
			// S가 이 행의 잠금을 기다리기 시작할 때까지 기다린 뒤 커밋한다.
			Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> lockWaits() > waitsBefore);
		});

		assertThatThrownBy(() -> renamed.get(10, TimeUnit.SECONDS)).cause()
			.isInstanceOfSatisfying(BusinessException.class,
					ex -> assertThat(ex.getErrorCode()).isEqualTo(MemberErrorCode.GUARDIAN_CHANGED));
		GuardianView after = studentService.get(student.id()).guardians().getFirst();
		assertThat(after.name()).isEqualTo(student.guardians().getFirst().name());
		assertThat(after.phone()).isEqualTo(changedByOther);
	}

	// InnoDB 잠금을 기다리는 트랜잭션 수. 다른 테스트가 같은 DB를 쓰므로 시작 전 값과 비교한다.
	// 앱의 DB 계정은 performance_schema를 읽을 수 없어, 같은 컨테이너의 root 계정으로 이 확인만 한다.
	private long lockWaits() throws SQLException {
		try (Connection root = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
				Statement statement = root.createStatement();
				ResultSet count = statement.executeQuery("SELECT COUNT(*) FROM performance_schema.data_lock_waits")) {
			count.next();
			return count.getLong(1);
		}
	}

}
