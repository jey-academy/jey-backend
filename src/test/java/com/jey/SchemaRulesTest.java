package com.jey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

// 마이그레이션을 모두 적용한 실제 DB의 테이블을 읽어 스키마 규칙을 본다.
//
// - 업무 테이블에는 created_at, updated_at, created_by가 있다(엔티티가 BaseEntity를 상속하면 채워진다).
//   새 테이블을 만들 때 빠뜨리면 여기서 실패한다.
// - 테이블 이름은 그 테이블을 가진 모듈의 접두사로 시작한다(core_, dongtanpass_, billing_).
//   다른 모듈의 테이블을 직접 읽지 않는다는 규칙이 이름으로 드러난다.
//
// 컬럼이 있는지만 본다. 타입과 null 가능 여부는 보지 않는다(엔티티가 있는 테이블의 타입은 서버가 뜰 때 Hibernate가 맞춰 본다).
// 뷰는 보지 않는다.
//
// campus_id는 보지 않는다. 지점에 속한 자료(반, 학생, 수납, 패스 사용 내역)에만 두고, 지점과 무관한 자료(학부모, 할인 유형,
// 연결 테이블)에는 두지 않는다. 어느 쪽인지는 자료의 뜻으로 정하는 것이라 여기서 가릴 수 없다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class SchemaRulesTest {

	private static final Set<String> COMMON_COLUMNS = Set.of("created_at", "updated_at", "created_by");

	private static final Set<String> MODULE_PREFIXES = Set.of("core_", "dongtanpass_", "billing_");

	// 우리가 모양을 정하지 않는 테이블: 이벤트 발행 기록(Spring Modulith)과 마이그레이션 기록(Flyway).
	private static final Set<String> NOT_OURS = Set.of("event_publication", "flyway_schema_history");

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void 업무_테이블에는_공통_컬럼이_있다() {
		Map<String, Set<String>> tables = allTables();

		// 하나도 못 찾았다면 읽는 방법이 틀린 것이다.
		assertThat(businessTables(tables)).as("업무 테이블").hasSizeGreaterThanOrEqualTo(8).containsKey("core_user");
		assertThat(missingCommonColumns(tables)).isEmpty();
	}

	@Test
	void 테이블_이름은_모듈의_접두사로_시작한다() {
		assertThat(unowned(allTables().keySet())).as("모듈의 접두사가 없는 테이블. 직접 만든 것이 아니면 NOT_OURS에 더한다")
			.isEmpty();
	}

	// 목록에만 남고 실제로는 없는 이름이 있으면, 그 예외는 낡은 것이다.
	@Test
	void 우리_것이_아니라고_적은_테이블은_실제로_있다() {
		assertThat(allTables().keySet()).containsAll(NOT_OURS);
	}

	// 예외 목록에 업무 테이블을 넣으면 공통 컬럼 검사에서 빠진다. 모듈의 접두사를 가진 이름은 예외가 될 수 없게 한다.
	@Test
	void 모듈의_접두사를_가진_테이블은_예외로_뺄_수_없다() {
		assertThat(NOT_OURS).noneMatch(SchemaRulesTest::hasModulePrefix);
	}

	// 아래 둘은 판정이 위반을 잡는지 DB 없이 본다(읽어 온 것 대신 손으로 만든 테이블 목록을 넣는다).
	@Test
	void 공통_컬럼이_빠진_업무_테이블을_잡고_우리_것이_아닌_테이블은_보지_않는다() {
		Map<String, Set<String>> tables = Map.of("core_complete",
				Set.of("id", "created_at", "updated_at", "created_by"), "core_partial", Set.of("id", "created_at"),
				"billing_bare", Set.of("id"), "event_publication", Set.of("id"));

		assertThat(missingCommonColumns(tables)).containsExactly("billing_bare에 [created_at, created_by, updated_at]가 없다",
				"core_partial에 [created_by, updated_at]가 없다");
	}

	@Test
	void 접두사가_없는_테이블을_잡고_우리_것이_아닌_테이블은_보지_않는다() {
		assertThat(unowned(Set.of("core_user", "dongtanpass_pass", "billing_invoice", "event_publication",
				"flyway_schema_history", "probe_thing", "corex_thing")))
			.containsExactly("corex_thing", "probe_thing");
	}

	// 규칙을 어긴 곳마다 한 줄. 비어 있으면 모두 지킨 것이다.
	private static List<String> missingCommonColumns(Map<String, Set<String>> tables) {
		List<String> missing = new ArrayList<>();
		businessTables(tables).forEach((table, columns) -> {
			Set<String> lacks = new TreeSet<>(COMMON_COLUMNS);
			lacks.removeAll(columns);
			if (!lacks.isEmpty()) {
				missing.add(table + "에 " + lacks + "가 없다");
			}
		});
		return missing;
	}

	private static Set<String> unowned(Set<String> tables) {
		Set<String> unowned = new TreeSet<>(tables);
		unowned.removeIf(table -> NOT_OURS.contains(table) || hasModulePrefix(table));
		return unowned;
	}

	private static Map<String, Set<String>> businessTables(Map<String, Set<String>> tables) {
		Map<String, Set<String>> business = new TreeMap<>(tables);
		business.keySet().removeAll(NOT_OURS);
		return business;
	}

	private static boolean hasModulePrefix(String table) {
		return MODULE_PREFIXES.stream().anyMatch(table::startsWith);
	}

	private Map<String, Set<String>> allTables() {
		Map<String, Set<String>> tables = new TreeMap<>();
		jdbc.query("""
				SELECT c.table_name, c.column_name
				FROM information_schema.columns c
				JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name
				WHERE c.table_schema = DATABASE() AND t.table_type = 'BASE TABLE'
				""", row -> {
			tables.computeIfAbsent(row.getString(1).toLowerCase(), table -> new TreeSet<>())
				.add(row.getString(2).toLowerCase());
		});
		// 다른 스키마를 보고 있거나 마이그레이션이 돌지 않았으면 비어 있다. 빈 목록으로는 어떤 규칙도 본 것이 아니다.
		assertThat(tables).as("지금 스키마의 테이블").isNotEmpty();
		return tables;
	}

}
