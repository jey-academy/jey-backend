package com.jey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API 명세는 프론트와의 계약이다. 서버가 내보내는 명세를 {@code docs/api/openapi.json}에 두고, 프론트는 그 파일에서 타입을 만든다.
 *
 * <p>이 테스트는 지금의 명세가 저장된 파일과 같은지 본다. API를 바꾸면 여기서 실패한다.
 * 의도한 변경이면 아래 명령으로 파일을 다시 쓰고 같이 커밋한다. 그러면 바뀐 계약이 PR의 diff로 보인다.
 *
 * <pre>./gradlew test --tests "*ApiSpecTest" -PupdateApiSpec</pre>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiSpecTest {

	private static final Path SPEC_FILE = Path.of("docs", "api", "openapi.json");

	private static final String UPDATE_PROPERTY = "jey.updateApiSpec";

	private static final String UPDATE_COMMAND = "./gradlew test --tests \"*ApiSpecTest\" -PupdateApiSpec";

	private static final JsonMapper MAPPER = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

	@Autowired
	MockMvc mockMvc;

	@Test
	void 저장된_명세와_현재_명세가_같다() throws Exception {
		Map<String, Object> current = currentSpec();

		if (Boolean.getBoolean(UPDATE_PROPERTY)) {
			write(current);
			return;
		}

		if (!Files.exists(SPEC_FILE)) {
			fail("API 명세 파일이 없다: %s%n만들기: %s", SPEC_FILE, UPDATE_COMMAND);
		}
		Map<String, Object> saved = normalize(Files.readString(SPEC_FILE, StandardCharsets.UTF_8));
		if (!current.equals(saved)) {
			fail("API 명세가 저장된 파일(%s)과 다르다.%n%s%n"
					+ "의도한 변경이면 파일을 다시 쓰고 같이 커밋한다: %s%n의도하지 않았다면 프론트와의 계약이 깨진 것이니 코드를 되돌린다.",
					SPEC_FILE, String.join(System.lineSeparator(), differences(saved, current)), UPDATE_COMMAND);
		}
	}

	// 테스트용 컨트롤러는 테스트 컨텍스트에만 있다. 명세에 섞이면 프론트 타입에 없는 API가 생긴다.
	@Test
	void 명세에_테스트_전용_경로가_없다() throws Exception {
		assertThat(paths(currentSpec()).keySet()).isNotEmpty().noneMatch(path -> path.contains("/test-"));
	}

	// 명세가 비어도 "저장된 것과 같다"는 통과한다. 실제 API가 들어 있는지 따로 본다.
	@Test
	void 명세에_실제_API_경로가_있다() throws Exception {
		assertThat(paths(currentSpec()).keySet()).contains("/api/v1/auth/login", "/api/v1/auth/me", "/api/v1/campuses",
				"/api/v1/classes", "/api/v1/class-fees", "/api/v1/discount-types");
	}

	private Map<String, Object> currentSpec() throws Exception {
		String body = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString(StandardCharsets.UTF_8);
		return normalize(body);
	}

	// 키를 정렬해서 실행할 때마다 같은 순서가 되게 하고, 환경마다 다른 값은 뺀다.
	private static Map<String, Object> normalize(String json) {
		@SuppressWarnings("unchecked")
		Map<String, Object> sorted = (Map<String, Object>) sortKeys(MAPPER.readValue(json, Object.class));
		// 요청한 주소가 들어간다(테스트에서는 http://localhost). 계약이 아니다.
		sorted.remove("servers");
		return sorted;
	}

	private static Object sortKeys(Object value) {
		if (value instanceof Map<?, ?> map) {
			Map<String, Object> sorted = new TreeMap<>();
			map.forEach((key, each) -> sorted.put(String.valueOf(key), sortKeys(each)));
			return sorted;
		}
		if (value instanceof List<?> list) {
			return list.stream().map(ApiSpecTest::sortKeys).toList();
		}
		return value;
	}

	// 줄바꿈은 LF로 쓴다. 어느 운영체제에서 다시 써도 같은 파일이 된다.
	private static void write(Map<String, Object> spec) throws IOException {
		String json = MAPPER.writeValueAsString(spec).replace("\r\n", "\n") + "\n";
		Files.createDirectories(SPEC_FILE.getParent());
		Files.writeString(SPEC_FILE, json, StandardCharsets.UTF_8);
	}

	// 어디가 달라졌는지 경로와 스키마 이름으로 알려준다. 자세한 내용은 파일을 다시 쓴 뒤의 diff로 본다.
	private static List<String> differences(Map<String, Object> saved, Map<String, Object> current) {
		List<String> differences = new ArrayList<>();
		describe("경로", paths(saved), paths(current), differences);
		describe("스키마", schemas(saved), schemas(current), differences);
		if (differences.isEmpty()) {
			differences.add("  경로와 스키마 밖(제목, 버전 등)이 달라졌다");
		}
		return differences;
	}

	private static void describe(String kind, Map<String, Object> saved, Map<String, Object> current,
			List<String> differences) {
		TreeSet<String> names = new TreeSet<>(saved.keySet());
		names.addAll(current.keySet());
		for (String name : names) {
			if (!saved.containsKey(name)) {
				differences.add("  추가된 " + kind + ": " + name);
			}
			else if (!current.containsKey(name)) {
				differences.add("  없어진 " + kind + ": " + name);
			}
			else if (!Objects.equals(saved.get(name), current.get(name))) {
				differences.add("  바뀐 " + kind + ": " + name);
			}
		}
	}

	private static Map<String, Object> paths(Map<String, Object> spec) {
		return child(spec, "paths");
	}

	private static Map<String, Object> schemas(Map<String, Object> spec) {
		return child(child(spec, "components"), "schemas");
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> child(Map<String, Object> parent, String key) {
		Object value = parent.get(key);
		return (value instanceof Map<?, ?>) ? (Map<String, Object>) value : Map.of();
	}

}
