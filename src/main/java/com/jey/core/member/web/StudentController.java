package com.jey.core.member.web;

import com.jey.core.auth.api.AllowedRoles;
import com.jey.core.auth.api.UserRole;
import com.jey.core.member.PhoneRevealService;
import com.jey.core.member.StudentService;
import com.jey.core.shared.Idempotent;
import com.jey.core.shared.NotIdempotent;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 학생은 지점으로 묶지 않는다. 직원은 다른 지점의 학생도 보고 고친다. 연계 학원은 학생을 보지 못한다.
// 전화번호는 가린 표시로만 나간다. 전체 번호는 맨 아래의 두 경로로만 나가고, 볼 때마다 기록과 횟수가 남는다(ADR-0026).
@RestController
@RequestMapping("/api/v1/students")
@AllowedRoles({ UserRole.ADMIN, UserRole.STAFF })
class StudentController {

	static final int MAX_PAGE_SIZE = 100;

	private final StudentService studentService;

	private final PhoneRevealService phoneRevealService;

	StudentController(StudentService studentService, PhoneRevealService phoneRevealService) {
		this.studentService = studentService;
		this.phoneRevealService = phoneRevealService;
	}

	// GET이 아니다. 조건을 주소로 받으면 이름과 전화번호 뒷자리가 브라우저의 방문 기록과 앞단의 접근 로그에 남는다 (ADR-0032).
	// 학생 목록을 받는 길은 이것 하나다. 주소로 조건을 받는 GET 목록을 다시 만들지 않는다.
	@Operation(summary = "학생 목록·검색",
			description = "조건은 모두 선택이고 본문으로 보낸다. 조건이 없으면 빈 객체({})를 보낸다. 이름 순이고 퇴원한 학생도 나온다. "
					+ "조회라서 Idempotency-Key는 받지 않는다")
	@NotIdempotent("바꾸는 자료가 없다. 다시 보내면 다시 조회할 뿐이다")
	@PostMapping("/search")
	StudentPageResponse search(@Valid @RequestBody StudentSearchRequest body) {
		return StudentPageResponse.from(
				studentService.search(body.campusId(), body.name(), body.phoneLast4(), body.page(), body.size()));
	}

	@Idempotent
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	StudentResponse register(@Valid @RequestBody StudentCreateRequest body) {
		return StudentResponse.from(studentService.register(body.toRegistration()));
	}

	@GetMapping("/{id}")
	StudentResponse student(@PathVariable long id) {
		return StudentResponse.from(studentService.get(id));
	}

	@PutMapping("/{id}")
	StudentResponse update(@PathVariable long id, @Valid @RequestBody StudentUpdateRequest body) {
		return StudentResponse.from(studentService.update(id, body.toChange()));
	}

	@DeleteMapping("/{id}/phone")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void removePhone(@PathVariable long id) {
		studentService.removePhone(id);
	}

	@Idempotent
	@PostMapping("/{id}/guardians")
	@ResponseStatus(HttpStatus.CREATED)
	StudentResponse addGuardian(@PathVariable long id, @Valid @RequestBody GuardianRequest body) {
		return StudentResponse.from(studentService.addGuardian(id, body.toInput()));
	}

	@PutMapping("/{id}/guardians/{guardianId}")
	StudentResponse changeRelation(@PathVariable long id, @PathVariable long guardianId,
			@Valid @RequestBody RelationUpdateRequest body) {
		return StudentResponse.from(studentService.changeRelation(id, guardianId, body.relation()));
	}

	@DeleteMapping("/{id}/guardians/{guardianId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void removeGuardian(@PathVariable long id, @PathVariable long guardianId) {
		studentService.removeGuardian(id, guardianId);
	}

	// GET이 아니다. 볼 때마다 기록과 횟수가 남는 요청이라 브라우저가 미리 읽거나 주소만으로 불리면 안 된다.
	@Operation(summary = "학생 본인의 전체 전화번호 보기",
			description = "볼 때마다 감사 로그에 남고 계정별 횟수에 든다. 한도를 넘으면 429와 Retry-After(초)가 온다")
	@NotIdempotent("바꾸는 자료가 없다. 다시 보내면 한 번 더 본 것으로 세고 적는다(ADR-0026)")
	@PostMapping("/{id}/phone/reveal")
	PhoneRevealResponse revealPhone(@PathVariable long id) {
		return PhoneRevealResponse.from(phoneRevealService.studentPhone(id));
	}

	@Operation(summary = "학부모의 전체 전화번호 보기",
			description = "그 학생에게 연결된 학부모만 된다. 볼 때마다 감사 로그에 남고 계정별 횟수에 든다. 한도를 넘으면 429와 Retry-After(초)가 온다")
	@NotIdempotent("바꾸는 자료가 없다. 다시 보내면 한 번 더 본 것으로 세고 적는다(ADR-0026)")
	@PostMapping("/{id}/guardians/{guardianId}/phone/reveal")
	PhoneRevealResponse revealGuardianPhone(@PathVariable long id, @PathVariable long guardianId) {
		return PhoneRevealResponse.from(phoneRevealService.guardianPhone(id, guardianId));
	}

}
