package com.jey.core.member;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.jey.core.member.domain.Grade;
import com.jey.core.member.domain.GuardianRelation;
import com.jey.core.shared.PhoneNumber;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 테스트끼리 DB를 같이 쓴다. 이름과 전화번호를 매번 새로 만들어 서로 겹치지 않게 한다.
 */
public final class MemberFixtures {

	public static final long DONGTAN = 1;

	public static final long NEUNGDONG = 2;

	private MemberFixtures() {
	}

	public static String uniqueName() {
		return "학생-" + UUID.randomUUID().toString().substring(0, 8);
	}

	public static PhoneNumber randomPhone() {
		return PhoneNumber.of("010" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000)));
	}

	/** 뒷자리가 정해진 임의의 번호. */
	public static PhoneNumber randomPhoneEndingWith(String last4) {
		return PhoneNumber.of("010" + String.format("%04d", ThreadLocalRandom.current().nextInt(10_000)) + last4);
	}

	public static String randomLast4() {
		return String.format("%04d", ThreadLocalRandom.current().nextInt(10_000));
	}

	public static GuardianInput mother(PhoneNumber phone) {
		return new GuardianInput("김어머니", GuardianRelation.MOTHER, phone);
	}

	public static GuardianInput father(PhoneNumber phone) {
		return new GuardianInput("김아버지", GuardianRelation.FATHER, phone);
	}

	/** 동탄관, 반 없음, 고1, 본인 번호 없음. */
	public static StudentRegistration registration(String name, GuardianInput... guardians) {
		return new StudentRegistration(DONGTAN, null, name, "동탄고", Grade.HIGH_1, null, null, List.of(guardians));
	}

	public static StudentRegistration registrationWithPhone(String name, PhoneNumber studentPhone,
			GuardianInput... guardians) {
		return new StudentRegistration(DONGTAN, null, name, "동탄고", Grade.HIGH_1, null, studentPhone,
				List.of(guardians));
	}

	/**
	 * 학생의 연락처를 모두 없앤다(학부모 연결과 본인 번호). 화면으로는 만들 수 없는 상태라 DB를 직접 고친다.
	 * 등록과 삭제가 막고 있지만, 자료가 그렇게 됐을 때 서버가 어떻게 답하는지 보려고 쓴다.
	 */
	public static void removeAllContacts(JdbcTemplate jdbc, long studentId) {
		jdbc.update("DELETE FROM core_student_guardian WHERE student_id = ?", studentId);
		jdbc.update("UPDATE core_student SET phone_enc = NULL, phone_bidx = NULL, phone_last4_bidx = NULL, "
				+ "key_version = NULL WHERE id = ?", studentId);
	}

}
