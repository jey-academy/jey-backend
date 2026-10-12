package com.jey.core.campus.web;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.campus.DiscountTypeService;
import com.jey.core.campus.domain.DiscountType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static com.jey.TestCsrf.csrfToken;
import static com.jey.TestIdempotency.idempotencyKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 테스트끼리 DB를 같이 쓴다. 코드에 임의 접미사를 붙이고, 목록은 그 코드로 걸러서 본다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DiscountTypeApiTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	DiscountTypeService discountTypeService;

	// 처음부터 있는 할인. 코드는 청구에 남는 값이라 바뀌면 안 된다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1)
	void 직원은_할인_유형과_할인율을_본다() throws Exception {
		mockMvc.perform(get("/api/v1/discount-types"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.code == 'SIBLING_5')].rate").value(0.05))
				.andExpect(jsonPath("$[?(@.code == 'SIBLING_5')].name").value("형제 할인 5%"))
				.andExpect(jsonPath("$[?(@.code == 'SIBLING_7')].rate").value(0.07))
				.andExpect(jsonPath("$[?(@.code == 'DONGTAN_PASS')].rate").value(0.05))
				.andExpect(jsonPath("$[?(@.code == 'DONGTAN_PASS')].active").value(true));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = 1)
	void 직원은_할인_유형을_만들거나_고치지_못한다() throws Exception {
		DiscountType existing = discountTypeService.create(uniqueCode(), "시험 할인", new BigDecimal("0.03"));

		create(uniqueCode(), "시험 할인", "0.03").andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("AUTH_ROLE_NOT_ALLOWED"));
		update(existing.getId(), "시험 할인", "0.9", true).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("AUTH_ROLE_NOT_ALLOWED"));
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = 7)
	void 연계_학원은_할인_유형을_보지_못한다() throws Exception {
		mockMvc.perform(get("/api/v1/discount-types"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("AUTH_ROLE_NOT_ALLOWED"));
	}

	// 연계 학원 계정은 학원 밖 사람이 쓴다. 조회뿐 아니라 변경도 막혀 있어야 한다.
	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = 7)
	void 연계_학원은_할인_유형을_만들거나_고치지_못한다() throws Exception {
		DiscountType existing = discountTypeService.create(uniqueCode(), "시험 할인", new BigDecimal("0.03"));

		create(uniqueCode(), "시험 할인", "0.03").andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("AUTH_ROLE_NOT_ALLOWED"));
		update(existing.getId(), "시험 할인", "0.9", false).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("AUTH_ROLE_NOT_ALLOWED"));
	}

	@Test
	void 로그인하지_않으면_401이다() throws Exception {
		mockMvc.perform(get("/api/v1/discount-types")).andExpect(status().isUnauthorized());
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 관리자가_할인_유형을_만들면_목록에_나온다() throws Exception {
		String code = uniqueCode();

		create(code, " 시험 할인 ", "0.0725").andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.code").value(code))
				.andExpect(jsonPath("$.name").value("시험 할인"))
				.andExpect(jsonPath("$.rate").value(0.0725))
				.andExpect(jsonPath("$.active").value(true));

		mockMvc.perform(get("/api/v1/discount-types"))
				.andExpect(jsonPath("$[?(@.code == '%s')].rate".formatted(code)).value(0.0725));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 관리자가_할인율과_이름을_고치고_코드는_그대로다() throws Exception {
		DiscountType existing = discountTypeService.create(uniqueCode(), "시험 할인", new BigDecimal("0.03"));

		update(existing.getId(), "바꾼 이름", "0.1", false).andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(existing.getId().intValue()))
				.andExpect(jsonPath("$.code").value(existing.getCode()))
				.andExpect(jsonPath("$.name").value("바꾼 이름"))
				.andExpect(jsonPath("$.rate").value(0.1))
				.andExpect(jsonPath("$.active").value(false));

		// 꺼진 할인도 목록에 남는다. 화면이 active로 가린다.
		mockMvc.perform(get("/api/v1/discount-types"))
				.andExpect(jsonPath("$[?(@.code == '%s')].active".formatted(existing.getCode())).value(false))
				.andExpect(jsonPath("$[?(@.code == '%s')].rate".formatted(existing.getCode())).value(0.1));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 같은_코드의_할인_유형은_만들_수_없다() throws Exception {
		String code = uniqueCode();
		create(code, "시험 할인", "0.03").andExpect(status().isCreated());

		create(code, "다른 이름", "0.05").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CAMPUS_DISCOUNT_CODE_DUPLICATED"));
		create("SIBLING_5", "형제", "0.05").andExpect(status().isConflict());
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 없는_할인_유형은_고칠_수_없다() throws Exception {
		update(999_999_999L, "시험 할인", "0.03", true).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CAMPUS_DISCOUNT_TYPE_NOT_FOUND"));
	}

	// 5%를 5로 넣는 실수가 가장 흔하다.
	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 할인율이_범위_밖이면_400이다() throws Exception {
		for (String rate : new String[] { "5", "0", "-0.05", "1.0001", "0.07255" }) {
			create(uniqueCode(), "시험 할인", rate).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
					.andExpect(jsonPath("$.errors[?(@.field == 'rate')]").isNotEmpty());
		}
	}

	// 수정 요청은 검증 애너테이션을 따로 갖고 있다. 5%를 5로 넣는 실수는 수정 화면에서 더 흔하다.
	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 수정할_할인율이_범위_밖이면_400이고_값은_그대로다() throws Exception {
		DiscountType existing = discountTypeService.create(uniqueCode(), "시험 할인", new BigDecimal("0.03"));

		for (String rate : new String[] { "5", "0", "-0.05", "1.0001", "0.07255" }) {
			update(existing.getId(), "시험 할인", rate, true).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
					.andExpect(jsonPath("$.errors[?(@.field == 'rate')]").isNotEmpty());
		}
		update(existing.getId(), "\u3000", "0.03", true).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));

		mockMvc.perform(get("/api/v1/discount-types"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.code == '%s')].rate".formatted(existing.getCode())).value(0.03))
				.andExpect(jsonPath("$[?(@.code == '%s')].name".formatted(existing.getCode())).value("시험 할인"));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 코드_형식이_틀리면_400이다() throws Exception {
		for (String code : new String[] { "sibling", "5_SIBLING", "SIBLING-5", "S" }) {
			create(code, "시험 할인", "0.05").andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
					.andExpect(jsonPath("$.errors[?(@.field == 'code')]").isNotEmpty());
		}
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 수정은_모든_값을_보내야_한다() throws Exception {
		DiscountType existing = discountTypeService.create(uniqueCode(), "시험 할인", new BigDecimal("0.03"));

		mockMvc.perform(put("/api/v1/discount-types/{id}", existing.getId()).with(csrfToken(mockMvc))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"시험 할인\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[?(@.field == 'rate')]").isNotEmpty())
				.andExpect(jsonPath("$.errors[?(@.field == 'active')]").isNotEmpty());
	}

	private ResultActions create(String code, String name, String rate) throws Exception {
		return mockMvc.perform(post("/api/v1/discount-types").with(csrfToken(mockMvc)).with(idempotencyKey())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\": \"%s\", \"name\": \"%s\", \"rate\": %s}".formatted(code, name, rate)));
	}

	private ResultActions update(long id, String name, String rate, boolean active) throws Exception {
		return mockMvc.perform(put("/api/v1/discount-types/{id}", id).with(csrfToken(mockMvc))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"%s\", \"rate\": %s, \"active\": %s}".formatted(name, rate, active)));
	}

	private static String uniqueCode() {
		return "T_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
	}

}
