package com.jey.core.auth.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import com.jey.core.auth.api.AccountRules;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 로그인하는 계정(직원, 관리자, 연계 학원). 학생·학부모 정보와는 별개다.
 */
@Entity
@Table(name = "core_user")
public class User extends BaseEntity {

	private static final int MAX_LOGIN_ID_LENGTH = 50;

	/** 요청을 받는 쪽의 검증도 같은 값({@link AccountRules})을 쓴다. */
	public static final int MAX_NAME_LENGTH = AccountRules.MAX_NAME_LENGTH;

	/** 영문 소문자, 숫자, 점, 밑줄, 하이픈만. 3~50자, 첫 글자는 영문이나 숫자. 요청을 받는 쪽의 검증도 같은 값({@link AccountRules})을 쓴다. */
	public static final String LOGIN_ID_PATTERN = AccountRules.LOGIN_ID_PATTERN;

	private static final Pattern LOGIN_ID = Pattern.compile(LOGIN_ID_PATTERN);

	// 인코더가 만든 해시의 모양: {알고리즘 이름} 뒤에 값이 온다.
	private static final Pattern ENCODED_PASSWORD = Pattern.compile("\\{[A-Za-z0-9@._-]+\\}.+");

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "login_id", nullable = false, updatable = false, length = MAX_LOGIN_ID_LENGTH)
	private String loginId;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Column(nullable = false, length = MAX_NAME_LENGTH)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private UserRole role;

	// 직원만 가진다. 관리자(전 지점)와 연계 학원은 지점이 없다.
	@Column(name = "campus_id")
	private Long campusId;

	// 연계 학원 계정만 가진다. 연계 학원 테이블은 제이G패스 모듈에 있어 외래 키는 없다.
	@Column(name = "partner_id")
	private Long partnerId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private UserStatus status;

	// 권한 판 번호 (ADR-0031). 세션에는 로그인할 때의 번호가 들어 있고, 요청마다 이 값과 비교한다.
	// 그 전에 만든 세션을 무효로 해야 하는 변경(차단, 역할·지점, 비밀번호)은 이 번호를 올린다(invalidateSessions).
	// JPA의 @Version(동시 변경을 가리는 번호)이 아니다.
	@Column(name = "auth_version", nullable = false)
	private long authVersion;

	protected User() {
	}

	private User(String loginId, String passwordHash, String name, UserRole role, Long campusId, Long partnerId) {
		this.loginId = loginId;
		this.passwordHash = passwordHash;
		this.name = name;
		this.role = role;
		this.campusId = campusId;
		this.partnerId = partnerId;
		this.status = UserStatus.ACTIVE;
	}

	/**
	 * @param loginId 앞뒤 공백을 떼고 소문자로 바꿔 저장한다({@link #normalizeLoginId})
	 * @param passwordHash 해시된 비밀번호. 원문을 넘기지 않는다.
	 * @throws IllegalArgumentException 필수값이 비었거나, 길이가 넘거나, 해시가 아니거나, 역할과 지점·연계 학원 식별자의 조합이 맞지 않을 때
	 */
	public static User create(String loginId, String passwordHash, String name, UserRole role, Long campusId,
			Long partnerId) {
		requireText(loginId, "loginId");
		requireText(passwordHash, "passwordHash");
		requireText(name, "name");
		if (role == null) {
			throw new IllegalArgumentException("role은 비울 수 없다");
		}
		String normalizedLoginId = normalizeLoginId(loginId);
		if (!isValidLoginId(normalizedLoginId)) {
			throw new IllegalArgumentException("loginId는 영문 소문자, 숫자, '.', '_', '-'로 3~50자여야 한다");
		}
		requireMaxLength(name, MAX_NAME_LENGTH, "name");
		requireHash(passwordHash);
		role.validateAffiliation(campusId, partnerId);
		return new User(normalizedLoginId, passwordHash, name, role, campusId, partnerId);
	}

	/**
	 * 아이디를 비교할 수 있는 형태로 맞춘다. 저장할 때와 찾을 때 같은 규칙을 써야 한다.
	 * DB는 대소문자를 구분하지 않고 Java는 구분하므로, 소문자로 통일해 둘의 판단이 어긋나지 않게 한다.
	 */
	public static String normalizeLoginId(String loginId) {
		return loginId.trim().toLowerCase(Locale.ROOT);
	}

	/**
	 * 맞춘 아이디가 허용하는 형식인지. 영문 소문자, 숫자, 점, 밑줄, 하이픈으로 3~50자다.
	 * DB는 악센트가 붙은 글자나 전각 문자를 같은 글자로 본다. 그런 문자를 허용하면 서로 다른 입력이 한 계정에 닿아서,
	 * 입력한 아이디 기준으로 로그인 시도를 세는 장치를 피할 수 있게 된다.
	 */
	public static boolean isValidLoginId(String normalizedLoginId) {
		return LOGIN_ID.matcher(normalizedLoginId).matches();
	}

	/**
	 * 다음 로그인부터 막는다. 이미 로그인된 세션은 다음 요청에서 막힌다(권한 판 번호가 오른다).
	 * 계정을 차단할 때는 세션까지 치우는 {@code AccountService#disable}을 쓴다.
	 */
	public void disable() {
		if (this.status == UserStatus.DISABLED) {
			return;
		}
		this.status = UserStatus.DISABLED;
		invalidateSessions();
	}

	/** 차단을 푼다. 다시 로그인할 수 있다. 차단하기 전에 만든 세션은 살아나지 않는다(차단할 때 권한 판 번호가 올랐다). */
	public void enable() {
		this.status = UserStatus.ACTIVE;
	}

	public boolean isActive() {
		return status == UserStatus.ACTIVE;
	}

	/**
	 * 로그인할 때 만든 세션(또는 방금 비밀번호를 검증한 로그인)이 지금의 이 계정으로 계속 일해도 되는지.
	 * 기준은 여기 한 곳이다: 요청마다의 확인과 로그인 직전의 재확인이 모두 이것을 쓴다.
	 *
	 * <p>차단되지 않았고, 권한 판 번호가 그때와 같고, 역할·지점·연계 학원 식별자가 세션의 것과 같아야 한다.
	 * 마지막 조건은 번호를 올리지 않고 바뀐 계정(DB를 직접 고친 경우)을 잡는다.
	 *
	 * @param sessionUser 세션에 든 로그인 정보. 이 계정의 것이어야 한다(부르는 쪽이 계정 ID로 이 계정을 찾는다)
	 * @param sessionAuthVersion 그 세션을 만들 때의 권한 판 번호
	 */
	public boolean stillValidFor(AuthenticatedUser sessionUser, long sessionAuthVersion) {
		return isActive() && authVersion == sessionAuthVersion && role == sessionUser.role()
				&& Objects.equals(campusId, sessionUser.campusId())
				&& Objects.equals(partnerId, sessionUser.partnerId());
	}

	/**
	 * 화면에 보일 이름을 고친다. 이미 로그인된 세션에 든 이름은 다음 로그인 때 바뀐다.
	 *
	 * @return 실제로 바뀌었는지
	 * @throws IllegalArgumentException 비었거나 길이가 넘을 때
	 */
	public boolean rename(String name) {
		requireText(name, "name");
		requireMaxLength(name, MAX_NAME_LENGTH, "name");
		if (name.equals(this.name)) {
			return false;
		}
		this.name = name;
		return true;
	}

	/**
	 * 역할과 소속 지점을 바꾼다. 이미 로그인된 세션은 다음 요청에서 막힌다(권한 판 번호가 오른다).
	 * 바꿀 때는 세션까지 치우는 {@code AccountService#update}를 쓴다.
	 *
	 * @return 실제로 바뀌었는지
	 * @throws IllegalArgumentException 역할이 비었거나, 역할과 지점·연계 학원 식별자의 조합이 맞지 않을 때
	 */
	public boolean changeAffiliation(UserRole role, Long campusId) {
		if (role == null) {
			throw new IllegalArgumentException("role은 비울 수 없다");
		}
		role.validateAffiliation(campusId, this.partnerId);
		if (role == this.role && Objects.equals(campusId, this.campusId)) {
			return false;
		}
		this.role = role;
		this.campusId = campusId;
		invalidateSessions();
		return true;
	}

	/**
	 * 비밀번호를 바꾼다. 이미 로그인된 세션은 다음 요청에서 막힌다(권한 판 번호가 오른다).
	 * 본인이 바꿀 때 지금 세션을 남기려면 그 세션에 새 번호를 넣어야 한다({@code AccountService#changeOwnPassword}).
	 *
	 * @param passwordHash 해시된 비밀번호. 원문을 넘기지 않는다.
	 * @throws IllegalArgumentException 비었거나 해시가 아닐 때
	 */
	public void changePassword(String passwordHash) {
		requireText(passwordHash, "passwordHash");
		requireHash(passwordHash);
		this.passwordHash = passwordHash;
		invalidateSessions();
	}

	public Long getId() {
		return id;
	}

	public String getLoginId() {
		return loginId;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public String getName() {
		return name;
	}

	public UserRole getRole() {
		return role;
	}

	public Long getCampusId() {
		return campusId;
	}

	public Long getPartnerId() {
		return partnerId;
	}

	public UserStatus getStatus() {
		return status;
	}

	public long getAuthVersion() {
		return authVersion;
	}

	// 이 계정으로 그 전에 만든 세션을 모두 무효로 한다 (ADR-0031). 세션에 든 것(역할, 소속)이나 로그인의 근거(비밀번호, 차단 여부)를
	// 바꾸는 메서드는 이것을 부른다. 그런 필드를 더하면 UserTest의 분류에도 더한다.
	// 계정 행을 잠근 트랜잭션에서 부른다. 잠그지 않은 두 변경이 겹치면 번호가 한 번만 오른다.
	private void invalidateSessions() {
		this.authVersion++;
	}

	// 인코더가 만든 해시는 {bcrypt}처럼 알고리즘 이름으로 시작하고 그 뒤에 값이 온다. 원문 비밀번호를 그대로 넘기는 실수를 여기서 막는다.
	// 모양만 본다. 그 모양을 한 원문({a}b 같은 것)까지 가려내지는 못한다.
	private static void requireHash(String passwordHash) {
		if (!ENCODED_PASSWORD.matcher(passwordHash).matches()) {
			throw new IllegalArgumentException("passwordHash는 해시된 값이어야 한다");
		}
	}

	private static void requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + "은(는) 비울 수 없다");
		}
	}

	private static void requireMaxLength(String value, int maxLength, String field) {
		if (value.length() > maxLength) {
			throw new IllegalArgumentException(field + "은(는) " + maxLength + "자를 넘을 수 없다");
		}
	}

}
