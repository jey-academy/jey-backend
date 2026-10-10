package com.jey.core.auth.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import com.jey.TestcontainersConfiguration;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class UserPersistenceTest {

	@Autowired
	UserRepository users;

	@Autowired
	Clock clock;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void 저장하면_생성_시각과_수정_시각이_채워진다() {
		Instant before = clock.instant();

		User saved = users.saveAndFlush(newUser(uniqueLoginId()));

		assertThat(saved.getId()).isNotNull();
		assertThat(saved.getCreatedAt()).isBetween(before.minusMillis(1), clock.instant());
		assertThat(saved.getUpdatedAt()).isEqualTo(saved.getCreatedAt());
	}

	@Test
	void 로그인한_사용자가_없으면_작성자는_비어_있다() {
		User saved = users.saveAndFlush(newUser(uniqueLoginId()));

		assertThat(saved.getCreatedBy()).isNull();
	}

	@Test
	void 로그인한_사용자가_저장하면_그_계정이_작성자로_남는다() {
		var loggedIn = new AuthenticatedUser(42L, "admin", "박원장", UserRole.ADMIN, null);
		SecurityContextHolder.getContext()
				.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(loggedIn, null, List.of()));
		try {
			User saved = users.saveAndFlush(newUser(uniqueLoginId()));

			assertThat(saved.getCreatedBy()).isEqualTo(42L);
			assertThat(users.findById(saved.getId()).orElseThrow().getCreatedBy()).isEqualTo(42L);
		}
		finally {
			SecurityContextHolder.clearContext();
		}
	}

	// DB는 마이크로초까지만 저장한다. 저장한 값과 다시 읽은 값이 달라지면 비교와 캐시에서 어긋난다.
	@Test
	void 저장한_시각과_다시_읽은_시각이_같다() {
		User saved = users.saveAndFlush(newUser(uniqueLoginId()));

		User found = users.findById(saved.getId()).orElseThrow();

		assertThat(found.getCreatedAt()).isEqualTo(saved.getCreatedAt());
		assertThat(found.getUpdatedAt()).isEqualTo(saved.getUpdatedAt());
	}

	// DB를 직접 열어 봤을 때 한국 시각으로 읽혀야 한다.
	// 테스트 JVM의 시간대는 한국도 UTC도 아닌 곳으로 고정돼 있어서(build.gradle.kts),
	// JVM 시간대로 저장되거나 UTC로 저장되면 이 테스트가 실패한다.
	// (Gradle로 돌릴 때 그렇다. IDE에서 직접 돌리면 JVM 시간대가 한국이라 JVM 시간대로 저장되는 경우는 가려내지 못한다.)
	@Test
	void DB에는_한국_시각으로_저장된다() {
		User saved = users.saveAndFlush(newUser(uniqueLoginId()));

		String stored = jdbc.queryForObject("SELECT CAST(created_at AS CHAR) FROM core_user WHERE id = ?",
				String.class, saved.getId());

		String koreanTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")
				.format(saved.getCreatedAt().atZone(ZoneId.of("Asia/Seoul")));
		assertThat(stored).isEqualTo(koreanTime);
	}

	@Test
	void 수정하면_수정_시각만_바뀐다() throws InterruptedException {
		User saved = users.saveAndFlush(newUser(uniqueLoginId()));
		Instant createdAt = saved.getCreatedAt();
		Thread.sleep(5);

		saved.disable();
		User updated = users.saveAndFlush(saved);

		assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
		assertThat(updated.getUpdatedAt()).isAfter(createdAt);
		assertThat(users.findById(saved.getId()).orElseThrow().isActive()).isFalse();
	}

	@Test
	void 아이디로_계정을_찾는다() {
		String loginId = uniqueLoginId();
		users.saveAndFlush(newUser(loginId));

		User found = users.findByLoginId(loginId).orElseThrow();

		assertThat(found.getLoginId()).isEqualTo(loginId);
		assertThat(found.getName()).isEqualTo("김직원");
		assertThat(found.getRole()).isEqualTo(UserRole.STAFF);
		assertThat(found.getCampusId()).isEqualTo(1L);
		assertThat(found.isActive()).isTrue();
		assertThat(users.findByLoginId("no-such-user")).isEmpty();
	}

	@Test
	void 같은_아이디는_두_번_저장할_수_없다() {
		String loginId = uniqueLoginId();
		users.saveAndFlush(newUser(loginId));

		assertThatThrownBy(() -> users.saveAndFlush(newUser(loginId)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	// DB는 대소문자를 구분하지 않는다. 저장할 때 소문자로 맞추므로 대소문자만 다른 아이디는 같은 아이디다.
	@Test
	void 대소문자만_다른_아이디는_같은_아이디로_본다() {
		String loginId = uniqueLoginId();
		users.saveAndFlush(newUser(loginId));

		assertThatThrownBy(() -> users.saveAndFlush(newUser(loginId.toUpperCase())))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(users.findByLoginId(User.normalizeLoginId(loginId.toUpperCase()))).isPresent();
	}

	private static User newUser(String loginId) {
		return User.create(loginId, "{noop}not-a-real-hash", "김직원", UserRole.STAFF, 1L);
	}

	private static String uniqueLoginId() {
		return "staff-" + UUID.randomUUID().toString().substring(0, 8);
	}

}
