package com.jey.billing.application;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.jey.core.member.api.MemberDirectory;
import com.jey.core.member.api.StudentInfo;

/**
 * 청구·결제 응답에 붙일 학생 이름. 목록은 한 페이지의 학생을 한 번에 조회한다(줄마다 부르지 않는다).
 * 학생은 지우지 않으므로 찾지 못하면 자료가 깨진 것이다 — 한 줄 때문에 목록이 500이 되지 않게 이름만 비우고 경고를 남긴다.
 */
@Component
class StudentNames {

	private static final Logger log = LoggerFactory.getLogger(StudentNames.class);

	private final MemberDirectory members;

	StudentNames(MemberDirectory members) {
		this.members = members;
	}

	/** 학생 ID → 이름. 찾지 못한 학생은 빠진다. */
	Map<Long, String> namesOf(Collection<Long> studentIds) {
		List<Long> distinct = studentIds.stream().distinct().toList();
		if (distinct.isEmpty()) {
			return Map.of();
		}
		Map<Long, String> names = members.findStudents(distinct)
			.stream()
			.collect(Collectors.toMap(StudentInfo::studentId, StudentInfo::name, (a, b) -> a));
		distinct.stream().filter(id -> !names.containsKey(id)).forEach(StudentNames::warnMissing);
		return names;
	}

	/** 그 학생의 이름. 찾지 못하면 {@code null}. */
	String nameOf(long studentId) {
		return namesOf(List.of(studentId)).get(studentId);
	}

	private static void warnMissing(long studentId) {
		log.warn("청구·결제의 학생을 찾지 못했다: studentId={}", studentId);
	}

}
