package com.jey.core.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import com.jey.TestcontainersConfiguration;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.NewPartnerAccount;
import com.jey.core.auth.api.PartnerAccount;
import com.jey.core.auth.api.PartnerAccounts;
import com.jey.core.auth.api.UserRole;
import com.jey.core.auth.domain.User;
import com.jey.core.auth.domain.UserRepository;
import com.jey.core.auth.domain.UserStatus;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.ErrorCode;
import com.jey.core.shared.RateLimitedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 계정 관리가 실제 DB와 Redis에서 도는지 본다(ADR-0030). 순서와 갈래는 AccountServiceTest가, 세션이 실제로 끊기는지는 API 테스트가 본다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class AccountManagementTest {

	private static final String PASSWORD = "first-password-123";

	private static final long DONGTAN = 1L;

	private static final long NEUNGDONG = 2L;

	// 비밀번호 해시는 계산이 느리다. 테스트마다 다시 만들지 않는다.
	private static String passwordHash;

	@Autowired
	AccountService accountService;

	@Autowired
	UserRepository users;

	@Autowired
	PasswordEncoder passwordEncoder;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	StringRedisTemplate redis;

	@Autowired
	PartnerAccounts partnerAccounts;

	@Autowired
	TransactionTemplate transaction;

	private User admin;

	@BeforeEach
	void setUp() {
		// 한도는 계정별로 세고 테스트마다 새 계정을 쓰지만, 규칙대로 비우고 시작한다.
		redis.delete(redis.keys("jey:auth:account-limit:*"));
		// 다른 테스트가 남긴 관리자 계정은 다시 쓰이지 않는다. "활성 관리자가 몇 명인가"를 보는 테스트가 그 수를 알 수 있게 막아 둔다.
		jdbc.update("UPDATE core_user SET status = 'DISABLED' WHERE role = 'ADMIN'");
		if (passwordHash == null) {
			passwordHash = passwordEncoder.encode(PASSWORD);
		}
		admin = saved("admin", "박원장", UserRole.ADMIN, null);
		actAs(admin);
	}

	@AfterEach
	void clearLogin() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void 직원_계정을_만들면_그_비밀번호로_확인되고_만든_사실이_기록된다() {
		String loginId = newLoginId("desk");

		AccountView created = accountService
			.create(new NewAccount(loginId, "김직원", "desk-password-123", UserRole.STAFF, DONGTAN));

		assertThat(created.loginId()).isEqualTo(loginId);
		assertThat(created.name()).isEqualTo("김직원");
		assertThat(created.role()).isEqualTo(UserRole.STAFF);
		assertThat(created.campusId()).isEqualTo(DONGTAN);
		assertThat(created.status()).isEqualTo(UserStatus.ACTIVE);
		assertThat(created.createdAt()).isNotNull();
		User stored = users.findById(created.id()).orElseThrow();
		assertThat(passwordEncoder.matches("desk-password-123", stored.getPasswordHash())).isTrue();
		assertThat(stored.getPasswordHash()).doesNotContain("desk-password-123");
		assertThat(detailOf("account.created", created.id())).contains("\"role\":\"STAFF\"")
			.doesNotContain(loginId)
			.doesNotContain("desk-password-123");
		assertThat(jdbc.queryForObject(
				"SELECT campus_id FROM core_audit_log WHERE action = 'account.created' AND target_id = ?", Long.class,
				created.id()))
			.isEqualTo(DONGTAN);
	}

	@Test
	void 관리자_계정은_지점_없이_만든다() {
		AccountView created = accountService
			.create(new NewAccount(newLoginId("owner"), "최원장", "owner-password-123", UserRole.ADMIN, null));

		assertThat(created.role()).isEqualTo(UserRole.ADMIN);
		assertThat(created.campusId()).isNull();
	}

	// DB는 대소문자를 가리지 않는다. 저장하기 전에 소문자로 맞추므로 대소문자와 앞뒤 공백만 다른 아이디는 같은 아이디다.
	@Test
	void 대소문자와_공백만_다른_아이디는_이미_쓰는_아이디이고_거부가_기록된다() {
		String loginId = newLoginId("desk");
		accountService.create(new NewAccount(loginId, "김직원", "desk-password-123", UserRole.STAFF, DONGTAN));
		int before = deniedBy("account.login-id-duplicated", admin);

		assertDenied(() -> accountService.create(new NewAccount("  " + loginId.toUpperCase() + " ", "이직원",
				"desk-password-456", UserRole.STAFF, NEUNGDONG)), AuthErrorCode.LOGIN_ID_DUPLICATED);

		assertThat(deniedBy("account.login-id-duplicated", admin)).isEqualTo(before + 1);
		assertThat(users.findByLoginId(loginId).orElseThrow().getName()).isEqualTo("김직원");
	}

	// 확인과 저장 사이에 다른 요청이 끼어든다. 유니크 제약에 걸린 쪽도 서버 오류가 아니라 "이미 쓰는 아이디"를 받아야 한다.
	@Test
	void 같은_아이디로_동시에_만들면_하나만_만들어지고_나머지는_중복으로_거부된다() throws Exception {
		String loginId = newLoginId("race");

		List<Object> results = concurrently(4, index -> () -> {
			actAs(admin);
			return accountService
				.create(new NewAccount(loginId, "직원" + index, "race-password-123", UserRole.STAFF, DONGTAN));
		});

		assertThat(results).filteredOn(AccountView.class::isInstance).hasSize(1);
		assertThat(results).filteredOn(result -> !(result instanceof AccountView))
			.hasSize(3)
			.allSatisfy(result -> assertThat(result).isInstanceOfSatisfying(BusinessException.class,
					ex -> assertThat(ex.getErrorCode()).isEqualTo(AuthErrorCode.LOGIN_ID_DUPLICATED)));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM core_user WHERE login_id = ?", Integer.class, loginId))
			.isEqualTo(1);
	}

	@Test
	void 역할과_지점의_조합이_맞지_않으면_만들_수_없다() {
		assertDenied(() -> accountService
			.create(new NewAccount(newLoginId("desk"), "김직원", "desk-password-123", UserRole.STAFF, null)),
				AuthErrorCode.ROLE_CAMPUS_MISMATCH);
		assertDenied(() -> accountService
			.create(new NewAccount(newLoginId("owner"), "최원장", "owner-password-123", UserRole.ADMIN, DONGTAN)),
				AuthErrorCode.ROLE_CAMPUS_MISMATCH);
	}

	@Test
	void 연계_학원_계정은_만들_수_없다() {
		assertDenied(() -> accountService
			.create(new NewAccount(newLoginId("partner"), "연계학원", "partner-password-1", UserRole.PARTNER, null)),
				AuthErrorCode.PARTNER_ACCOUNT_NOT_SUPPORTED);
	}

	// 없는 지점은 DB의 외래 키가 걸러낸다. 서버 오류가 아니라 "없는 지점"으로 알린다.
	@Test
	void 없는_지점의_직원은_만들_수도_옮길_수도_없다() {
		String loginId = newLoginId("desk");
		assertDenied(() -> accountService
			.create(new NewAccount(loginId, "김직원", "desk-password-123", UserRole.STAFF, 999_999L)),
				AuthErrorCode.CAMPUS_NOT_FOUND);
		assertThat(users.findByLoginId(loginId)).isEmpty();
		User staff = saved("desk", "김직원", UserRole.STAFF, DONGTAN);

		assertDenied(() -> accountService.update(staff.getId(), new AccountChange("김직원", UserRole.STAFF, 999_999L)),
				AuthErrorCode.CAMPUS_NOT_FOUND);

		assertThat(users.findById(staff.getId()).orElseThrow().getCampusId()).isEqualTo(DONGTAN);
		assertThat(recordsOf("account.updated", staff.getId())).isZero();
	}

	@Test
	void 규칙에_맞지_않는_비밀번호로는_만들_수_없다() {
		String loginId = newLoginId("desk-with-long-id");

		for (String password : new String[] { "short", "가".repeat(25), loginId, loginId.toUpperCase() }) {
			assertDenied(
					() -> accountService.create(new NewAccount(loginId, "김직원", password, UserRole.STAFF, DONGTAN)),
					AuthErrorCode.PASSWORD_NOT_ACCEPTABLE);
		}
		assertThat(users.findByLoginId(loginId)).isEmpty();
	}

	@Test
	void 지점을_옮기고_관리자로_올리면_무엇이_바뀌었는지_기록된다() {
		User staff = saved("desk", "김직원", UserRole.STAFF, DONGTAN);

		AccountView moved = accountService.update(staff.getId(), new AccountChange("김직원", UserRole.STAFF, NEUNGDONG));
		// 어느 지점으로 옮겼는지가 기록에 있다.
		assertThat(jdbc.queryForObject(
				"SELECT campus_id FROM core_audit_log WHERE action = 'account.updated' AND target_id = ?", Long.class,
				staff.getId()))
			.isEqualTo(NEUNGDONG);
		AccountView promoted = accountService.update(staff.getId(), new AccountChange("김실장", UserRole.ADMIN, null));

		assertThat(moved.campusId()).isEqualTo(NEUNGDONG);
		assertThat(promoted.role()).isEqualTo(UserRole.ADMIN);
		assertThat(promoted.campusId()).isNull();
		assertThat(promoted.name()).isEqualTo("김실장");
		assertThat(recordsOf("account.updated", staff.getId())).isEqualTo(2);
		assertThat(detailOf("account.updated", staff.getId())).contains("\"nameChanged\":true")
			.contains("\"affiliationChanged\":true")
			.contains("\"role\":\"ADMIN\"")
			.doesNotContain("김실장");
	}

	@Test
	void 바뀐_것이_없는_고치기는_기록을_남기지_않는다() {
		User staff = saved("desk", "김직원", UserRole.STAFF, DONGTAN);

		accountService.update(staff.getId(), new AccountChange("김직원", UserRole.STAFF, DONGTAN));

		assertThat(recordsOf("account.updated", staff.getId())).isZero();
	}

	@Test
	void 자기_자신은_차단하거나_역할을_바꿀_수_없고_거부가_기록된다() {
		assertDenied(() -> accountService.disable(admin.getId()), AuthErrorCode.SELF_CHANGE_NOT_ALLOWED);
		assertDenied(() -> accountService.update(admin.getId(), new AccountChange("박원장", UserRole.STAFF, DONGTAN)),
				AuthErrorCode.SELF_CHANGE_NOT_ALLOWED);
		assertDenied(() -> accountService.resetPassword(admin.getId(), "another-password-1"),
				AuthErrorCode.SELF_CHANGE_NOT_ALLOWED);

		assertThat(recordsOf("account.self-change-denied", admin.getId())).isEqualTo(3);
		User stored = users.findById(admin.getId()).orElseThrow();
		assertThat(stored.isActive()).isTrue();
		assertThat(stored.getRole()).isEqualTo(UserRole.ADMIN);
		assertThat(passwordEncoder.matches(PASSWORD, stored.getPasswordHash())).isTrue();
	}

	@Test
	void 차단하고_풀면_각각_한_번씩_기록된다() {
		User staff = saved("desk", "김직원", UserRole.STAFF, DONGTAN);

		assertThat(accountService.disable(staff.getId()).status()).isEqualTo(UserStatus.DISABLED);
		assertThat(accountService.disable(staff.getId()).status()).isEqualTo(UserStatus.DISABLED);
		assertThat(accountService.enable(staff.getId()).status()).isEqualTo(UserStatus.ACTIVE);
		assertThat(accountService.enable(staff.getId()).status()).isEqualTo(UserStatus.ACTIVE);

		assertThat(recordsOf("account.disabled", staff.getId())).isEqualTo(1);
		assertThat(recordsOf("account.enabled", staff.getId())).isEqualTo(1);
	}

	// 관리자 둘이 서로를 동시에 차단한다. 잠그지 않고 세면 둘 다 "아직 한 명 더 있다"고 보고 통과해 관리자가 0명이 된다.
	@Test
	void 관리자_둘이_서로를_동시에_차단하면_하나만_되고_활성_관리자가_남는다() throws Exception {
		User other = saved("admin", "최원장", UserRole.ADMIN, null);

		List<Object> results = concurrently(2, index -> () -> {
			User actor = (index == 0) ? admin : other;
			User target = (index == 0) ? other : admin;
			actAs(actor);
			return accountService.disable(target.getId());
		});

		assertThat(results).filteredOn(AccountView.class::isInstance).hasSize(1);
		assertThat(results).filteredOn(result -> !(result instanceof AccountView))
			.singleElement()
			.isInstanceOfSatisfying(BusinessException.class,
					ex -> assertThat(ex.getErrorCode()).isEqualTo(AuthErrorCode.LAST_ADMIN));
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM core_user WHERE role = 'ADMIN' AND status = 'ACTIVE' AND id IN (?, ?)",
				Integer.class, admin.getId(), other.getId()))
			.isEqualTo(1);
		assertThat(recordsOf("account.last-admin-denied", admin.getId())
				+ recordsOf("account.last-admin-denied", other.getId()))
			.isEqualTo(1);
	}

	@Test
	void 비밀번호를_다시_정하면_새_비밀번호로만_확인되고_차단은_그대로다() {
		User left = saved("left", "퇴사자", UserRole.STAFF, DONGTAN);
		accountService.disable(left.getId());

		accountService.resetPassword(left.getId(), "brand-new-password");

		User stored = users.findById(left.getId()).orElseThrow();
		assertThat(passwordEncoder.matches("brand-new-password", stored.getPasswordHash())).isTrue();
		assertThat(passwordEncoder.matches(PASSWORD, stored.getPasswordHash())).isFalse();
		assertThat(stored.isActive()).isFalse();
		assertThat(recordsOf("account.password-reset", left.getId())).isEqualTo(1);
		assertThat(detailOf("account.password-reset", left.getId())).isNullOrEmpty();
	}

	@Test
	void 지금_비밀번호가_맞으면_자기_비밀번호를_바꾼다() {
		User staff = saved("desk", "김직원", UserRole.STAFF, DONGTAN);
		actAs(staff);

		accountService.changeOwnPassword(PASSWORD, "my-new-password-1", session("this-session"));

		String stored = users.findById(staff.getId()).orElseThrow().getPasswordHash();
		assertThat(passwordEncoder.matches("my-new-password-1", stored)).isTrue();
		assertThat(passwordEncoder.matches(PASSWORD, stored)).isFalse();
		assertThat(recordsOf("account.password-changed", staff.getId())).isEqualTo(1);
	}

	@Test
	void 지금_비밀번호가_틀리면_바뀌지_않고_거부가_기록된다() {
		User staff = saved("desk", "김직원", UserRole.STAFF, DONGTAN);
		actAs(staff);

		assertDenied(() -> accountService.changeOwnPassword("wrong-password-1", "my-new-password-1", session("this-session")),
				AuthErrorCode.CURRENT_PASSWORD_MISMATCH);

		assertThat(passwordEncoder.matches(PASSWORD, users.findById(staff.getId()).orElseThrow().getPasswordHash()))
			.isTrue();
		assertThat(recordsOf("account.password-change-mismatch", staff.getId())).isEqualTo(1);
		assertThat(recordsOf("account.password-changed", staff.getId())).isZero();
	}

	@Test
	void 새_비밀번호가_규칙에_맞지_않거나_지금과_같으면_바꿀_수_없다() {
		User staff = saved("desk", "김직원", UserRole.STAFF, DONGTAN);
		actAs(staff);

		assertDenied(() -> accountService.changeOwnPassword(PASSWORD, "short", session("this-session")),
				AuthErrorCode.PASSWORD_NOT_ACCEPTABLE);
		assertDenied(() -> accountService.changeOwnPassword(PASSWORD, staff.getLoginId(), session("this-session")),
				AuthErrorCode.PASSWORD_NOT_ACCEPTABLE);
		assertDenied(() -> accountService.changeOwnPassword(PASSWORD, PASSWORD, session("this-session")),
				AuthErrorCode.PASSWORD_UNCHANGED);

		assertThat(recordsOf("account.password-changed", staff.getId())).isZero();
	}

	// 세는 것은 틀린 지금 비밀번호뿐이다. 맞게 바꾼 요청과, 지금 비밀번호는 맞았는데 다른 이유로 거부된 요청은 세지 않는다.
	@Test
	void 틀린_지금_비밀번호가_다섯_번을_넘으면_맞는_비밀번호로도_막히고_막힌_사실이_한_번_기록된다() {
		User staff = saved("desk", "김직원", UserRole.STAFF, DONGTAN);
		actAs(staff);
		accountService.changeOwnPassword(PASSWORD, "my-new-password-1", session("this-session"));
		accountService.changeOwnPassword("my-new-password-1", "my-new-password-2", session("this-session"));
		for (int i = 0; i < 3; i++) {
			assertDenied(() -> accountService.changeOwnPassword("my-new-password-2", "short", session("this-session")),
					AuthErrorCode.PASSWORD_NOT_ACCEPTABLE);
		}
		for (int i = 0; i < 5; i++) {
			assertDenied(() -> accountService.changeOwnPassword("wrong-password-1", "my-new-password-3", session("this-session")),
					AuthErrorCode.CURRENT_PASSWORD_MISMATCH);
		}

		for (int i = 0; i < 2; i++) {
			assertThatThrownBy(
					() -> accountService.changeOwnPassword("my-new-password-2", "my-new-password-3", session("this-session")))
				.isInstanceOfSatisfying(RateLimitedException.class,
						ex -> assertThat(ex.getErrorCode()).isEqualTo(AuthErrorCode.TOO_MANY_PASSWORD_ATTEMPTS));
		}

		assertThat(passwordEncoder.matches("my-new-password-2",
				users.findById(staff.getId()).orElseThrow().getPasswordHash()))
			.isTrue();
		assertThat(recordsOf("account.password-change-mismatch", staff.getId())).isEqualTo(5);
		assertThat(recordsOf("account.password-change-limited", staff.getId())).isEqualTo(1);
	}

	// 조건마다 그 조건이 없으면 딸려 나올 계정을 이 테스트 안에서 만든다. 앞 테스트가 남긴 계정에 기대지 않는다.
	@Test
	void 조건으로_계정을_찾는다() {
		User dongtan = saved("desk", "김직원", UserRole.STAFF, DONGTAN);
		User neungdong = saved("desk", "이직원", UserRole.STAFF, NEUNGDONG);
		User leftNeungdong = saved("desk", "퇴사자", UserRole.STAFF, NEUNGDONG);
		User leftAdmin = saved("admin", "전 원장", UserRole.ADMIN, null);
		accountService.disable(leftNeungdong.getId());
		accountService.disable(leftAdmin.getId());

		assertThat(ids(accountService.search(UserRole.ADMIN, null, UserStatus.ACTIVE, 0, 100)))
			.containsExactly(admin.getId());
		assertThat(ids(accountService.search(UserRole.ADMIN, null, UserStatus.DISABLED, 0, 100)))
			.contains(leftAdmin.getId())
			.doesNotContain(admin.getId());
		assertThat(ids(accountService.search(UserRole.STAFF, NEUNGDONG, UserStatus.ACTIVE, 0, 100)))
			.contains(neungdong.getId())
			.doesNotContain(dongtan.getId(), leftNeungdong.getId(), admin.getId());
		assertThat(ids(accountService.search(null, NEUNGDONG, null, 0, 100)))
			.contains(neungdong.getId(), leftNeungdong.getId())
			.doesNotContain(dongtan.getId(), admin.getId());
		assertThat(ids(accountService.search(null, null, null, 0, 100))).contains(admin.getId(), dongtan.getId(),
				neungdong.getId(), leftNeungdong.getId(), leftAdmin.getId());
		assertThat(accountService.get(dongtan.getId()).name()).isEqualTo("김직원");
	}

	// 아이디 순이다. 직접 만든 두 계정의 앞뒤만 본다(전체가 자바의 글자 순서와 같은지는 DB의 정렬 규칙에 달렸다).
	@Test
	void 목록은_아이디_순이고_페이지로_나눈다() {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		User second = users.saveAndFlush(User.create("order-b-" + suffix, passwordHash, "나", UserRole.STAFF, DONGTAN, null));
		User first = users.saveAndFlush(User.create("order-a-" + suffix, passwordHash, "가", UserRole.STAFF, DONGTAN, null));

		List<Long> all = ids(accountService.search(UserRole.STAFF, DONGTAN, UserStatus.ACTIVE, 0, 100));

		assertThat(all.indexOf(first.getId())).isNotNegative().isLessThan(all.indexOf(second.getId()));
		Page<AccountView> onePerPage = accountService.search(UserRole.STAFF, DONGTAN, UserStatus.ACTIVE, 0, 1);
		assertThat(onePerPage.getContent()).hasSize(1);
		assertThat(onePerPage.getTotalElements()).isGreaterThanOrEqualTo(2);
	}

	// ADR: 연계 학원 계정은 여기서 만들지 않고 역할도 바꾸지 않지만, 이름 고치기·차단·비밀번호 다시 정하기는 된다.
	@Test
	void 연계_학원_계정은_차단하고_비밀번호를_다시_정할_수_있지만_역할은_바꿀_수_없다() {
		User partner = users
			.saveAndFlush(User.create(newLoginId("partner"), passwordHash, "연계학원", UserRole.PARTNER, null, 7L));

		assertDenied(() -> accountService.update(partner.getId(), new AccountChange("연계학원", UserRole.STAFF, DONGTAN)),
				AuthErrorCode.PARTNER_ACCOUNT_NOT_SUPPORTED);
		assertThat(accountService.update(partner.getId(), new AccountChange("새 이름", UserRole.PARTNER, null)).name())
			.isEqualTo("새 이름");
		accountService.resetPassword(partner.getId(), "partner-new-password");
		assertThat(accountService.disable(partner.getId()).status()).isEqualTo(UserStatus.DISABLED);

		User stored = users.findById(partner.getId()).orElseThrow();
		assertThat(stored.getRole()).isEqualTo(UserRole.PARTNER);
		assertThat(stored.getPartnerId()).isEqualTo(7L);
		assertThat(stored.isActive()).isFalse();
		assertThat(passwordEncoder.matches("partner-new-password", stored.getPasswordHash())).isTrue();
	}

	// 해시 함수는 널 문자에서 읽기를 멈춘다. 받아 주면 그 앞부분만으로 로그인된다.
	@Test
	void 제어_문자가_든_비밀번호로는_만들거나_바꿀_수_없다() {
		String loginId = newLoginId("desk");
		User staff = saved("desk", "김직원", UserRole.STAFF, DONGTAN);

		assertDenied(() -> accountService
			.create(new NewAccount(loginId, "김직원", "abcd\u0000abcd\u0000abcd", UserRole.STAFF, DONGTAN)),
				AuthErrorCode.PASSWORD_NOT_ACCEPTABLE);
		assertDenied(() -> accountService.resetPassword(staff.getId(), "first-line\nsecond-line"),
				AuthErrorCode.PASSWORD_NOT_ACCEPTABLE);
		actAs(staff);
		assertDenied(() -> accountService.changeOwnPassword(PASSWORD, "\u200B".repeat(12), session("this-session")),
				AuthErrorCode.PASSWORD_NOT_ACCEPTABLE);

		assertThat(users.findByLoginId(loginId)).isEmpty();
		assertThat(passwordEncoder.matches(PASSWORD, users.findById(staff.getId()).orElseThrow().getPasswordHash()))
			.isTrue();
	}

	// 둘 다 같은 "지금 비밀번호"를 확인하고 통과한다. 계정 행을 잠그고 확인한 해시가 그대로인지 다시 보지 않으면 둘 다 바꾼다.
	// 뒤에 온 쪽은 앞에 온 쪽이 바꾼 비밀번호를, 이제는 옛것이 된 비밀번호만 알고 덮어쓰게 된다.
	@Test
	void 같은_계정의_비밀번호를_동시에_바꾸면_하나만_바뀐다() throws Exception {
		User staff = saved("desk", "김직원", UserRole.STAFF, DONGTAN);

		List<Object> results = concurrently(2, index -> () -> {
			actAs(staff);
			accountService.changeOwnPassword(PASSWORD, "changed-by-request-" + index, session("session-" + index));
			return "changed-by-request-" + index;
		});

		assertThat(results).filteredOn(String.class::isInstance).hasSize(1);
		assertThat(results).filteredOn(result -> !(result instanceof String))
			.singleElement()
			.isInstanceOfSatisfying(BusinessException.class,
					ex -> assertThat(ex.getErrorCode()).isEqualTo(AuthErrorCode.CURRENT_PASSWORD_MISMATCH));
		String winner = (String) results.stream().filter(String.class::isInstance).findFirst().orElseThrow();
		assertThat(passwordEncoder.matches(winner, users.findById(staff.getId()).orElseThrow().getPasswordHash()))
			.isTrue();
		assertThat(recordsOf("account.password-changed", staff.getId())).isEqualTo(1);
		assertThat(recordsOf("account.password-change-mismatch", staff.getId())).isEqualTo(1);
	}

	// 비밀번호가 얽힌 기록에는 대상 말고 아무것도 싣지 않는다.
	@Test
	void 비밀번호를_바꾸거나_틀린_기록에는_상세가_없다() {
		User staff = saved("desk", "김직원", UserRole.STAFF, DONGTAN);
		actAs(staff);
		assertDenied(() -> accountService.changeOwnPassword("wrong-password-1", "my-new-password-1", session("this-session")),
				AuthErrorCode.CURRENT_PASSWORD_MISMATCH);
		accountService.changeOwnPassword(PASSWORD, "my-new-password-1", session("this-session"));

		assertThat(detailOf("account.password-change-mismatch", staff.getId())).isNullOrEmpty();
		assertThat(detailOf("account.password-changed", staff.getId())).isNullOrEmpty();
	}

	// 연계 학원 계정은 패스 모듈이 이 창구로 만든다(ADR-0034). 연계 학원이 있는지는 부르는 쪽이 확인한다.
	@Test
	void 연계_학원_계정을_만들면_그_학원에_속하고_만든_기록에_학원이_남는다() {
		long partnerId = newPartnerId();
		String loginId = newLoginId("partner");

		PartnerAccount created = partnerAccounts
			.create(new NewPartnerAccount(partnerId, loginId, "햇살수학", "partner-password-123"));

		assertThat(created.loginId()).isEqualTo(loginId);
		assertThat(created.name()).isEqualTo("햇살수학");
		assertThat(created.partnerId()).isEqualTo(partnerId);
		assertThat(created.createdAt()).isNotNull();
		User stored = users.findById(created.id()).orElseThrow();
		assertThat(stored.getRole()).isEqualTo(UserRole.PARTNER);
		assertThat(stored.getPartnerId()).isEqualTo(partnerId);
		assertThat(stored.getCampusId()).isNull();
		assertThat(stored.isActive()).isTrue();
		assertThat(passwordEncoder.matches("partner-password-123", stored.getPasswordHash())).isTrue();
		assertThat(detailOf("account.created", created.id())).contains("\"role\":\"PARTNER\"")
			.contains("\"partnerId\":" + partnerId)
			.doesNotContain(loginId)
			.doesNotContain("partner-password-123");
	}

	@Test
	void 연계_학원_계정의_아이디도_소문자로_맞추고_이미_쓰는_아이디면_거부가_기록된다() {
		String loginId = newLoginId("partner");

		PartnerAccount created = partnerAccounts.create(
				new NewPartnerAccount(newPartnerId(), "  " + loginId.toUpperCase() + " ", "햇살수학", "partner-password-123"));
		int before = deniedBy("account.login-id-duplicated", admin);

		assertThat(created.loginId()).isEqualTo(loginId);
		assertDenied(() -> partnerAccounts
			.create(new NewPartnerAccount(newPartnerId(), loginId, "달빛영어", "partner-password-456")),
				AuthErrorCode.LOGIN_ID_DUPLICATED);
		assertThat(deniedBy("account.login-id-duplicated", admin)).isEqualTo(before + 1);
	}

	@Test
	void 연계_학원_계정도_비밀번호_규칙을_따른다() {
		assertDenied(() -> partnerAccounts
			.create(new NewPartnerAccount(newPartnerId(), newLoginId("partner"), "햇살수학", "short")),
				AuthErrorCode.PASSWORD_NOT_ACCEPTABLE);
	}

	// 만들고 커밋한 뒤의 일(감사 기록과 같이 커밋)이 바깥 트랜잭션에 합류하면 순서가 깨진다. 계정 관리의 다른 메서드와 같다.
	@Test
	void 연계_학원_계정의_창구는_트랜잭션_안에서_부를_수_없다() {
		long partnerId = newPartnerId();

		assertThatThrownBy(() -> transaction.executeWithoutResult(status -> partnerAccounts
			.create(new NewPartnerAccount(partnerId, newLoginId("partner"), "햇살수학", "partner-password-123"))))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> transaction.executeWithoutResult(status -> partnerAccounts.disableAll(partnerId)))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void 연계_학원의_활성_계정을_모두_차단하고_이미_차단된_계정과_다른_학원의_계정은_건드리지_않는다() {
		long partnerId = newPartnerId();
		long otherPartnerId = newPartnerId();
		PartnerAccount first = newPartnerAccount(partnerId);
		PartnerAccount second = newPartnerAccount(partnerId);
		PartnerAccount alreadyDisabled = newPartnerAccount(partnerId);
		accountService.disable(alreadyDisabled.id());
		PartnerAccount other = newPartnerAccount(otherPartnerId);

		int disabled = partnerAccounts.disableAll(partnerId);

		assertThat(disabled).isEqualTo(2);
		assertThat(users.findById(first.id()).orElseThrow().isActive()).isFalse();
		assertThat(users.findById(second.id()).orElseThrow().isActive()).isFalse();
		assertThat(users.findById(other.id()).orElseThrow().isActive()).isTrue();
		assertThat(recordsOf("account.disabled", first.id())).isEqualTo(1);
		assertThat(recordsOf("account.disabled", alreadyDisabled.id())).isEqualTo(1);
		assertThat(partnerAccounts.disableAll(partnerId)).isZero();
	}

	@Test
	void 계정_관리의_만들기로는_여전히_연계_학원_계정을_만들_수_없다() {
		assertDenied(() -> accountService.create(new NewAccount(newLoginId("partner"), "햇살수학",
				"partner-password-123", UserRole.PARTNER, null)), AuthErrorCode.PARTNER_ACCOUNT_NOT_SUPPORTED);
	}

	private PartnerAccount newPartnerAccount(long partnerId) {
		return partnerAccounts
			.create(new NewPartnerAccount(partnerId, newLoginId("partner"), "연계학원", "partner-password-123"));
	}

	// 연계 학원 테이블은 패스 모듈에 있고 계정은 그 식별자만 가진다(외래 키 없음). 테스트끼리 겹치지 않는 값을 쓴다.
	private static long newPartnerId() {
		return ThreadLocalRandom.current().nextLong(1_000_000L, Long.MAX_VALUE / 2);
	}

	private static List<Long> ids(Page<AccountView> page) {
		return page.getContent().stream().map(AccountView::id).toList();
	}

	private User saved(String prefix, String name, UserRole role, Long campusId) {
		return users.saveAndFlush(User.create(newLoginId(prefix), passwordHash, name, role, campusId, null));
	}

	private static String newLoginId(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
	}

	// 실제 로그인과 같은 모양의 로그인 정보를 이 스레드에 넣는다.
	private static void actAs(User user) {
		var principal = new AuthenticatedUser(user.getId(), user.getLoginId(), user.getName(), user.getRole(),
				user.getCampusId(), user.getPartnerId());
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null,
				List.of(new SimpleGrantedAuthority(user.getRole().authority()))));
		SecurityContextHolder.setContext(context);
	}

	private int recordsOf(String action, long accountId) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM core_audit_log WHERE action = ? AND target_type = 'account' AND target_id = ?",
				Integer.class, action, accountId);
	}

	// 대상이 없는 거부(아이디 중복)는 누가 받았는지로 센다.
	private int deniedBy(String action, User actor) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM core_audit_log WHERE action = ? AND actor_id = ?",
				Integer.class, action, actor.getId());
	}

	// 가장 마지막에 적힌 줄의 상세
	private String detailOf(String action, long accountId) {
		return jdbc.queryForObject("SELECT detail_json FROM core_audit_log WHERE action = ? AND target_id = ? "
				+ "ORDER BY id DESC LIMIT 1", String.class, action, accountId);
	}

	private static void assertDenied(Runnable call, ErrorCode expected) {
		assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class,
				ex -> assertThat(ex.getErrorCode()).isEqualTo(expected));
	}

	// 같은 순간에 시작해 각각의 결과(돌려준 값 또는 던진 예외)를 모은다.
	private static List<Object> concurrently(int count, java.util.function.IntFunction<Callable<Object>> taskOf)
			throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(count);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<Object>> futures = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				Callable<Object> task = taskOf.apply(i);
				futures.add(pool.submit(() -> {
					start.await();
					try {
						return task.call();
					}
					catch (Exception ex) {
						return ex;
					}
					finally {
						SecurityContextHolder.clearContext();
					}
				}));
			}
			start.countDown();
			List<Object> results = new ArrayList<>();
			for (Future<Object> future : futures) {
				results.add(future.get(30, TimeUnit.SECONDS));
			}
			return results;
		}
		finally {
			pool.shutdownNow();
		}
	}

	private static MockHttpSession session(String id) {
		return new MockHttpSession(null, id);
	}

}
