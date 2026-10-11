package com.jey.core.member;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 전화번호가 이미 있는지 드러나는 응답을 누가 받았는지 남긴다.
 *
 * <p>화면에는 가린 번호만 나가지만, "그 번호는 이미 있다"는 응답을 되풀이해 받으면 가운데 자리를 대입으로 알아낼 수 있다.
 * 전화번호 하나에 학부모 한 명을 지키는 한 이 응답은 없앨 수 없다(ADR-0023). 대신 한 계정이 되풀이하면 로그에서 보이게 한다.
 * 번호, 암호문, 인덱스는 남기지 않는다. 계정 ID와 학생·학부모 ID만 남긴다.
 */
final class PhoneLookupLog {

	/** 학부모 번호를 바꾸려 했는데 다른 학부모가 쓰는 번호였다. */
	static final String PHONE_DUPLICATED = "GUARDIAN_PHONE_DUPLICATED";

	/** 학부모 번호를 바꿨다. */
	static final String PHONE_CHANGED = "GUARDIAN_PHONE_CHANGED";

	/** 학생에게 붙이려던 번호의 학부모가 이미 그 학생에게 붙어 있었다. */
	static final String ALREADY_LINKED = "GUARDIAN_ALREADY_LINKED";

	/** 입력한 번호의 학부모가 이미 있어서 그 학부모에 붙였다. */
	static final String LINKED_TO_EXISTING = "LINKED_TO_EXISTING";

	private static final Logger log = LoggerFactory.getLogger(PhoneLookupLog.class);

	private PhoneLookupLog() {
	}

	/**
	 * @param studentId 학생과 무관한 일이면 {@code null}
	 */
	static void record(String event, Long studentId, Long guardianId) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		log.info("전화번호 대조: event={} account={} student={} guardian={}", event,
				(authentication != null) ? authentication.getName() : null, studentId, guardianId);
	}

}
