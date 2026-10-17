package com.jey.core.member.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 다른 모듈이 학생과 그 연락처를 확인하는 창구. 조회만 한다.
 *
 * <p>학생은 지점으로 묶지 않는다. 지점 범위가 필요한 자료(수납, 패스 사용 내역)는 그 자료를 가진 모듈이 건다.
 */
public interface MemberDirectory {

	/** 퇴원한 학생도 돌려준다. 이미 만든 청구가 그 학생을 참조한다. 새로 고르는 자리에서는 {@code enrolled}를 확인한다. */
	Optional<StudentInfo> findStudent(long studentId);

	/**
	 * 여러 학생을 한 번에 찾는다(목록 화면에서 학생마다 조회하지 않게). 퇴원한 학생도 돌려준다.
	 * 목록 한 페이지 분량(수십~백 명)에 쓴다. 크기를 제한하지 않으므로 전체 학생을 넘기지 않는다.
	 *
	 * @return 찾은 학생만. 없는 ID는 빠지고 순서는 정하지 않는다
	 */
	List<StudentInfo> findStudents(Collection<Long> studentIds);

	/**
	 * 이 학생에게 연결된 학부모 중 이 번호의 학부모가 있는지. 학생 본인 번호는 보지 않는다.
	 * 번호는 원문이 아니라 전체 번호 색인({@code PhoneProtector#fullIndex})으로 주고받는다.
	 *
	 * @return 없는 학생이면 {@code false}
	 */
	boolean hasGuardianWithPhoneIndex(long studentId, String phoneFullIndex);

	/**
	 * 형제의 학생 ID. 같은 학부모에 연결된 다른 학생이다. 퇴원한 학생도 포함한다.
	 * 형제 할인을 줄지는 직원이 고른다. 이 목록은 참고용이다.
	 *
	 * @return 없는 학생이거나 형제가 없으면 빈 목록
	 */
	List<Long> siblingIdsOf(long studentId);

	/**
	 * 알림과 링크를 보낼 대표 연락처. 어머니, 먼저 연결된 학부모, 학생 본인 번호 순으로 고른다.
	 *
	 * @return 없는 학생이면 비어 있다. 학생은 있는데 연락처가 하나도 없을 때도 비어 있다(보낼 곳이 없다).
	 * 빈 값을 받은 쪽은 조용히 건너뛰지 않는다. 보내려던 것을 보내지 못했다는 사실을 남긴다
	 */
	Optional<ContactInfo> primaryContact(long studentId);

}
