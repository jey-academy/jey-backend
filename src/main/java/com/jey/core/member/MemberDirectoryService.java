package com.jey.core.member;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.jey.core.member.api.ContactInfo;
import com.jey.core.member.api.MemberDirectory;
import com.jey.core.member.api.StudentInfo;
import com.jey.core.member.domain.Student;
import com.jey.core.member.domain.StudentGuardianRepository;
import com.jey.core.member.domain.StudentRepository;
import com.jey.core.member.domain.StudentStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class MemberDirectoryService implements MemberDirectory {

	private final StudentRepository students;

	private final StudentGuardianRepository studentGuardians;

	private final StudentService studentService;

	MemberDirectoryService(StudentRepository students, StudentGuardianRepository studentGuardians,
			StudentService studentService) {
		this.students = students;
		this.studentGuardians = studentGuardians;
		this.studentService = studentService;
	}

	@Override
	public Optional<StudentInfo> findStudent(long studentId) {
		return students.findById(studentId).map(MemberDirectoryService::infoOf);
	}

	@Override
	public List<StudentInfo> findStudents(Collection<Long> studentIds) {
		if (studentIds.isEmpty()) {
			return List.of();
		}
		return students.findAllById(studentIds).stream().map(MemberDirectoryService::infoOf).toList();
	}

	@Override
	public boolean hasGuardianWithPhoneIndex(long studentId, String phoneFullIndex) {
		return studentGuardians.existsGuardianWithPhoneIndex(studentId, phoneFullIndex);
	}

	@Override
	public List<Long> siblingIdsOf(long studentId) {
		return students.findSiblings(studentId).stream().map(Student::getId).toList();
	}

	@Override
	public Optional<ContactInfo> primaryContact(long studentId) {
		if (!students.existsById(studentId)) {
			return Optional.empty();
		}
		// 연락처가 하나도 없는 학생이면 비어 있다(StudentService가 경고를 남긴다).
		return Optional.ofNullable(studentService.get(studentId).primaryContact())
			.map(contact -> new ContactInfo(contact.name(), contact.studentOwn(), contact.phone()));
	}

	private static StudentInfo infoOf(Student student) {
		return new StudentInfo(student.getId(), student.getCampusId(), student.getClassId(), student.getName(),
				student.getStatus() == StudentStatus.ENROLLED, student.getCreatedAt());
	}

}
