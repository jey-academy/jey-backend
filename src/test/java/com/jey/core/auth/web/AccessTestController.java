package com.jey.core.auth.web;

import com.jey.core.auth.api.AllowedRoles;
import com.jey.core.auth.api.AnyRole;
import com.jey.core.auth.api.UserRole;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 접근 규칙만 검증하기 위한 테스트 전용 컨트롤러. 규칙을 걸 실제 기능이 아직 없어서 둔다.
// 테스트 소스라 운영 빌드에는 포함되지 않는다.
@RestController
@RequestMapping("/api/v1/test-access")
@AllowedRoles({ UserRole.ADMIN, UserRole.STAFF })
class AccessTestController {

	// 클래스의 표시를 따른다.
	@GetMapping("/desk")
	String desk() {
		return "ok";
	}

	@GetMapping("/admin")
	@AllowedRoles(UserRole.ADMIN)
	String admin() {
		return "ok";
	}

	// 클래스의 표시보다 넓지도 좁지도 않은, 다른 역할이다.
	@GetMapping("/partner")
	@AllowedRoles(UserRole.PARTNER)
	String partner() {
		return "ok";
	}

	@GetMapping("/any")
	@AnyRole
	String any() {
		return "ok";
	}

}
