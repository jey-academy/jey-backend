package com.jey.core.member;

import java.util.List;
import java.util.Optional;

import com.jey.core.member.StudentView.ContactView;
import com.jey.core.member.api.ContactInfo;
import com.jey.core.member.api.MemberDirectory;
import com.jey.core.member.api.StudentInfo;
import com.jey.core.member.domain.Student;
import com.jey.core.member.domain.StudentRepository;
import com.jey.core.member.domain.StudentStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class MemberDirectoryService implements MemberDirectory {

	private final StudentRepository students;

	private final StudentService studentService;

	MemberDirectoryService(StudentRepository students, StudentService studentService) {
		this.students = students;
		this.studentService = studentService;
	}

	@Override
	public Optional<StudentInfo> findStudent(long studentId) {
		return students.findById(studentId)
				.map(found -> new StudentInfo(found.getId(), found.getCampusId(), found.getClassId(), found.getName(),
						found.getStatus() == StudentStatus.ENROLLED));
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
		ContactView contact = studentService.get(studentId).primaryContact();
		return Optional.of(new ContactInfo(contact.name(), contact.studentOwn(), contact.phone()));
	}

}
