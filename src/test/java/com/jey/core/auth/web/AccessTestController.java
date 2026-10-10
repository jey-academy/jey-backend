package com.jey.core.auth.web;

import com.jey.core.auth.api.AllowedRoles;
import com.jey.core.auth.api.AnyRole;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.CampusScope;
import com.jey.core.auth.api.UserRole;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

	// 소속 지점으로 묶은 자료의 단건 조회 흉내.
	@GetMapping("/invoices/{campusId}")
	String invoice(@AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedUser user,
			@PathVariable long campusId) {
		user.requireCampus(campusId);
		return "ok";
	}

	// 소속 지점으로 묶은 자료의 목록 조회 흉내. 어느 범위로 조회했는지 돌려준다.
	@GetMapping("/invoices")
	String invoices(@AuthenticationPrincipal(errorOnInvalidType = true) AuthenticatedUser user,
			@RequestParam(required = false) Long campusId) {
		return switch (user.campusScope(campusId)) {
			case CampusScope.All all -> "all";
			case CampusScope.Only only -> "campus:" + only.campusId();
		};
	}

	// 묶지 않은 자료 흉내. 범위를 확인하지 않는다.
	@GetMapping("/students/{campusId}")
	String student(@PathVariable long campusId) {
		return "ok";
	}

}
