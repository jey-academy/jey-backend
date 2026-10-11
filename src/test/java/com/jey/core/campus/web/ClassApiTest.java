package com.jey.core.campus.web;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.jey.TestcontainersConfiguration;
import com.jey.WithJeyUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.campus.ClassService;
import com.jey.core.campus.domain.AcademyClass;
import com.jey.core.shared.Money;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import static com.jey.TestCsrf.csrfToken;
import static org.hamcrest.Matchers.startsWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 테스트끼리 DB를 같이 쓴다. 반 이름에 임의 접미사를 붙이고, 목록은 그 이름으로 걸러서 본다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClassApiTest {

	private static final long DONGTAN = 1;

	private static final long NEUNGDONG = 2;

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ClassService classService;

	// 수강료는 관리자만 본다. 직원이 받는 응답에는 금액 필드 자체가 없어야 한다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 직원이_보는_반_목록에는_수강료가_없다() throws Exception {
		String name = uniqueName();
		classService.create(DONGTAN, name, Money.of(280_000), Money.of(30_000));

		String body = mockMvc.perform(get("/api/v1/classes"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.name == '%s')].campusId".formatted(name)).value((int) DONGTAN))
				.andExpect(jsonPath("$[?(@.name == '%s')].id".formatted(name)).isNotEmpty())
				.andReturn()
				.getResponse()
				.getContentAsString();

		// 금액 필드의 이름을 하나씩 막지 않는다. 허용한 필드만 있어야 하므로, 무엇이든 필드가 늘면 여기서 걸린다.
		List<Map<String, Object>> classes = JsonMapper.builder().build().readValue(body, new TypeReference<>() {
		});
		assertThat(classes).isNotEmpty()
				.allSatisfy(each -> assertThat(each.keySet()).isEqualTo(Set.of("id", "campusId", "name")));
	}

	// 반은 지점으로 묶지 않는다.
	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 직원은_다른_지점의_반도_보고_지점을_골라_볼_수_있다() throws Exception {
		String dongtan = uniqueName();
		String neungdong = uniqueName();
		classService.create(DONGTAN, dongtan, Money.of(280_000), Money.ZERO);
		classService.create(NEUNGDONG, neungdong, Money.of(280_000), Money.ZERO);

		mockMvc.perform(get("/api/v1/classes"))
				.andExpect(jsonPath("$[?(@.name == '%s')]".formatted(dongtan)).isNotEmpty())
				.andExpect(jsonPath("$[?(@.name == '%s')]".formatted(neungdong)).isNotEmpty());
		mockMvc.perform(get("/api/v1/classes").param("campusId", String.valueOf(NEUNGDONG)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.name == '%s')]".formatted(dongtan)).isEmpty())
				.andExpect(jsonPath("$[?(@.name == '%s')]".formatted(neungdong)).isNotEmpty())
				.andExpect(jsonPath("$[?(@.campusId != %d)]".formatted(NEUNGDONG)).isEmpty());
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 직원은_자기_지점의_수강료표도_보지_못한다() throws Exception {
		mockMvc.perform(get("/api/v1/class-fees"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("AUTH_ROLE_NOT_ALLOWED"));
		mockMvc.perform(get("/api/v1/class-fees").param("campusId", String.valueOf(DONGTAN)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("AUTH_ROLE_NOT_ALLOWED"));
	}

	@Test
	@WithJeyUser(role = UserRole.STAFF, campusId = DONGTAN)
	void 직원은_반을_만들거나_고치지_못한다() throws Exception {
		AcademyClass existing = classService.create(DONGTAN, uniqueName(), Money.of(280_000), Money.ZERO);

		create(DONGTAN, uniqueName(), "280000", "0").andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("AUTH_ROLE_NOT_ALLOWED"));
		update(existing.getId(), existing.getName(), "1", "0", true).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("AUTH_ROLE_NOT_ALLOWED"));
	}

	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = 7)
	void 연계_학원은_반_목록도_보지_못한다() throws Exception {
		mockMvc.perform(get("/api/v1/classes"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("AUTH_ROLE_NOT_ALLOWED"));
		mockMvc.perform(get("/api/v1/class-fees"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("AUTH_ROLE_NOT_ALLOWED"));
	}

	// 연계 학원 계정은 학원 밖 사람이 쓴다. 조회뿐 아니라 변경도 막혀 있어야 한다.
	@Test
	@WithJeyUser(role = UserRole.PARTNER, partnerId = 7)
	void 연계_학원은_반을_만들거나_고치지_못한다() throws Exception {
		AcademyClass existing = classService.create(DONGTAN, uniqueName(), Money.of(280_000), Money.ZERO);

		create(DONGTAN, uniqueName(), "280000", "0").andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("AUTH_ROLE_NOT_ALLOWED"));
		update(existing.getId(), existing.getName(), "1", "0", false).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("AUTH_ROLE_NOT_ALLOWED"));
	}

	@Test
	void 로그인하지_않으면_401이다() throws Exception {
		mockMvc.perform(get("/api/v1/classes")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/class-fees")).andExpect(status().isUnauthorized());
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 관리자가_반을_만들면_수강료표에_나온다() throws Exception {
		String name = uniqueName();

		create(NEUNGDONG, "  " + name + " ", "280000", "30000").andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.campusId").value((int) NEUNGDONG))
				.andExpect(jsonPath("$.name").value(name))
				.andExpect(jsonPath("$.baseFee8").value(280000))
				.andExpect(jsonPath("$.bookFee").value(30000))
				.andExpect(jsonPath("$.active").value(true));

		mockMvc.perform(get("/api/v1/class-fees").param("campusId", String.valueOf(NEUNGDONG)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.name == '%s')].baseFee8".formatted(name)).value(280000))
				.andExpect(jsonPath("$[?(@.name == '%s')].bookFee".formatted(name)).value(30000));
		mockMvc.perform(get("/api/v1/class-fees").param("campusId", String.valueOf(DONGTAN)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$[?(@.name == '%s')]".formatted(name)).isEmpty());
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 관리자도_반_목록은_수강료_없이_본다() throws Exception {
		String name = uniqueName();
		classService.create(DONGTAN, name, Money.of(280_000), Money.ZERO);

		mockMvc.perform(get("/api/v1/classes"))
				.andExpect(jsonPath("$[?(@.name == '%s')]".formatted(name)).isNotEmpty())
				.andExpect(jsonPath("$[*].baseFee8").doesNotExist());
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 관리자가_수강료와_이름을_고친다() throws Exception {
		AcademyClass existing = classService.create(DONGTAN, uniqueName(), Money.of(280_000), Money.of(30_000));
		String renamed = uniqueName();

		update(existing.getId(), renamed, "300000", "0", true).andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(existing.getId().intValue()))
				.andExpect(jsonPath("$.campusId").value((int) DONGTAN))
				.andExpect(jsonPath("$.name").value(renamed))
				.andExpect(jsonPath("$.baseFee8").value(300000))
				.andExpect(jsonPath("$.bookFee").value(0));

		mockMvc.perform(get("/api/v1/class-fees"))
				.andExpect(jsonPath("$[?(@.id == %d)].baseFee8".formatted(existing.getId())).value(300000))
				.andExpect(jsonPath("$[?(@.id == %d)].name".formatted(existing.getId())).value(renamed));
	}

	// 이름을 그대로 둔 채 금액만 고치는 것이 가장 흔한 수정이다. 자기 자신과 이름이 겹친다고 막으면 안 된다.
	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 이름을_그대로_두고_금액만_고칠_수_있다() throws Exception {
		AcademyClass existing = classService.create(DONGTAN, uniqueName(), Money.of(280_000), Money.ZERO);

		update(existing.getId(), existing.getName(), "290000", "0", true).andExpect(status().isOk())
				.andExpect(jsonPath("$.baseFee8").value(290000));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 끈_반은_반_목록에서_빠지고_수강료표에는_남는다() throws Exception {
		AcademyClass existing = classService.create(DONGTAN, uniqueName(), Money.of(280_000), Money.ZERO);
		AcademyClass stillActive = classService.create(DONGTAN, uniqueName(), Money.of(280_000), Money.ZERO);

		update(existing.getId(), existing.getName(), "280000", "0", false).andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(false));

		// 요청이 오류로 끝나도 "목록에 없음"은 통과한다. 켜진 반이 보이는 것까지 같이 확인한다.
		mockMvc.perform(get("/api/v1/classes"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id == %d)]".formatted(stillActive.getId())).isNotEmpty())
				.andExpect(jsonPath("$[?(@.id == %d)]".formatted(existing.getId())).isEmpty());
		mockMvc.perform(get("/api/v1/class-fees"))
				.andExpect(jsonPath("$[?(@.id == %d)].active".formatted(existing.getId())).value(false));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 같은_지점에_같은_이름의_반은_만들_수_없다() throws Exception {
		String name = uniqueName();
		create(DONGTAN, name, "280000", "0").andExpect(status().isCreated());

		create(DONGTAN, name, "300000", "0").andExpect(status().isConflict())
				.andExpect(header().string("Content-Type", startsWith("application/problem+json")))
				.andExpect(jsonPath("$.code").value("CAMPUS_CLASS_NAME_DUPLICATED"));
		// 앞뒤 공백만 다른 이름도 같은 이름이다.
		create(DONGTAN, " " + name + " ", "300000", "0").andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CAMPUS_CLASS_NAME_DUPLICATED"));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 다른_지점에는_같은_이름의_반을_만들_수_있다() throws Exception {
		String name = uniqueName();
		create(DONGTAN, name, "280000", "0").andExpect(status().isCreated());

		create(NEUNGDONG, name, "280000", "0").andExpect(status().isCreated());
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 다른_반이_쓰는_이름으로는_바꿀_수_없다() throws Exception {
		AcademyClass first = classService.create(DONGTAN, uniqueName(), Money.of(280_000), Money.ZERO);
		AcademyClass second = classService.create(DONGTAN, uniqueName(), Money.of(280_000), Money.ZERO);

		update(second.getId(), first.getName(), "280000", "0", true).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CAMPUS_CLASS_NAME_DUPLICATED"));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 없는_지점에는_반을_만들_수_없다() throws Exception {
		create(999_999, uniqueName(), "280000", "0").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("CAMPUS_INVALID_CAMPUS"));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 없는_반은_고칠_수_없다() throws Exception {
		update(999_999_999L, uniqueName(), "280000", "0", true).andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("CAMPUS_CLASS_NOT_FOUND"));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 금액이_1원_단위가_아니거나_수강료가_0원이면_400이다() throws Exception {
		create(DONGTAN, uniqueName(), "280000.5", "0").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("CAMPUS_INVALID_FEE"));
		create(DONGTAN, uniqueName(), "280000", "0.5").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("CAMPUS_INVALID_FEE"));
		create(DONGTAN, uniqueName(), "0", "0").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("CAMPUS_INVALID_FEE"));
	}

	// 음수는 금액으로 읽히지 않는다. 본문을 읽는 단계에서 걸린다.
	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 음수_금액은_400이다() throws Exception {
		create(DONGTAN, uniqueName(), "-1", "0").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_BAD_REQUEST"));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 필수값이_빠지면_어느_필드인지_알려준다() throws Exception {
		mockMvc.perform(post("/api/v1/classes").with(csrfToken(mockMvc))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \" \"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[?(@.field == 'campusId')]").isNotEmpty())
				.andExpect(jsonPath("$.errors[?(@.field == 'name')]").isNotEmpty())
				.andExpect(jsonPath("$.errors[?(@.field == 'baseFee8')]").isNotEmpty())
				.andExpect(jsonPath("$.errors[?(@.field == 'bookFee')]").isNotEmpty());
	}

	// 빠진 필드를 "그대로 둔다"로 읽으면 운영 여부를 빼먹은 요청이 반을 끄게 된다.
	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 수정은_모든_값을_보내야_한다() throws Exception {
		AcademyClass existing = classService.create(DONGTAN, uniqueName(), Money.of(280_000), Money.ZERO);

		mockMvc.perform(put("/api/v1/classes/{id}", existing.getId()).with(csrfToken(mockMvc))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"%s\", \"baseFee8\": 280000, \"bookFee\": 0}".formatted(existing.getName())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[?(@.field == 'active')]").isNotEmpty());
	}

	// 수정 요청은 검증 애너테이션을 따로 갖고 있다. 생성만 확인하면 수정 쪽에서 빠져도 모른다.
	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 수정할_금액이나_이름이_틀리면_400이고_값은_그대로다() throws Exception {
		AcademyClass existing = classService.create(DONGTAN, uniqueName(), Money.of(280_000), Money.of(30_000));

		update(existing.getId(), existing.getName(), "0", "0", true).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("CAMPUS_INVALID_FEE"));
		update(existing.getId(), existing.getName(), "280000.5", "0", true).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("CAMPUS_INVALID_FEE"));
		update(existing.getId(), existing.getName(), "280000", "0.5", true).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("CAMPUS_INVALID_FEE"));
		update(existing.getId(), "가".repeat(51), "280000", "0", true).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));

		mockMvc.perform(get("/api/v1/class-fees"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.id == %d)].baseFee8".formatted(existing.getId())).value(280000))
				.andExpect(jsonPath("$[?(@.id == %d)].bookFee".formatted(existing.getId())).value(30000));
	}

	// 한글 입력기에서 나오는 전각 공백(U+3000)도 공백이다. 요청 검증이 놓치면 엔티티에서 걸려 500이 된다.
	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 전각_공백뿐인_이름은_400이다() throws Exception {
		AcademyClass existing = classService.create(DONGTAN, uniqueName(), Money.of(280_000), Money.ZERO);

		create(DONGTAN, "\u3000\u3000", "280000", "0").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"))
				.andExpect(jsonPath("$.errors[?(@.field == 'name')]").isNotEmpty());
		update(existing.getId(), "\u3000", "280000", "0", true).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
	}

	// 이름 중복은 같은 지점 안에서만 본다.
	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 다른_지점의_반이_쓰는_이름으로는_바꿀_수_있다() throws Exception {
		AcademyClass neungdong = classService.create(NEUNGDONG, uniqueName(), Money.of(280_000), Money.ZERO);
		AcademyClass dongtan = classService.create(DONGTAN, uniqueName(), Money.of(280_000), Money.ZERO);

		update(dongtan.getId(), neungdong.getName(), "280000", "0", true).andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value(neungdong.getName()));
	}

	@Test
	@WithJeyUser(role = UserRole.ADMIN)
	void 이름이_50자를_넘으면_400이다() throws Exception {
		create(DONGTAN, "가".repeat(51), "280000", "0").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMON_VALIDATION_FAILED"));
	}

	private ResultActions create(long campusId, String name, String baseFee8, String bookFee) throws Exception {
		return mockMvc.perform(post("/api/v1/classes").with(csrfToken(mockMvc))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"campusId\": %d, \"name\": \"%s\", \"baseFee8\": %s, \"bookFee\": %s}".formatted(campusId,
						name, baseFee8, bookFee)));
	}

	private ResultActions update(long id, String name, String baseFee8, String bookFee, boolean active)
			throws Exception {
		return mockMvc.perform(put("/api/v1/classes/{id}", id).with(csrfToken(mockMvc))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"%s\", \"baseFee8\": %s, \"bookFee\": %s, \"active\": %s}".formatted(name,
						baseFee8, bookFee, active)));
	}

	private static String uniqueName() {
		return "반-" + UUID.randomUUID().toString().substring(0, 8);
	}

}
