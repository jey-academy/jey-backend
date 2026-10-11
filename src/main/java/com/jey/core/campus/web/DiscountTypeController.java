package com.jey.core.campus.web;

import java.util.List;

import com.jey.core.auth.api.AllowedRoles;
import com.jey.core.auth.api.UserRole;
import com.jey.core.campus.DiscountTypeService;
import com.jey.core.shared.Idempotent;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/discount-types")
class DiscountTypeController {

	private final DiscountTypeService discountTypeService;

	DiscountTypeController(DiscountTypeService discountTypeService) {
		this.discountTypeService = discountTypeService;
	}

	// 직원이 적용할 할인을 고르려면 할인율까지 보여야 한다. 꺼진 것도 내보내고 화면이 active로 가린다.
	@AllowedRoles({ UserRole.ADMIN, UserRole.STAFF })
	@GetMapping
	List<DiscountTypeResponse> list() {
		return discountTypeService.all().stream().map(DiscountTypeResponse::from).toList();
	}

	@AllowedRoles(UserRole.ADMIN)
	@Idempotent
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	DiscountTypeResponse create(@Valid @RequestBody DiscountTypeCreateRequest body) {
		return DiscountTypeResponse.from(discountTypeService.create(body.code(), body.name(), body.rate()));
	}

	@AllowedRoles(UserRole.ADMIN)
	@PutMapping("/{id}")
	DiscountTypeResponse update(@PathVariable long id, @Valid @RequestBody DiscountTypeUpdateRequest body) {
		return DiscountTypeResponse
				.from(discountTypeService.update(id, body.name(), body.rate(), body.active()));
	}

}
