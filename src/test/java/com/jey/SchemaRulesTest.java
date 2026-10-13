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
// campus_id는 보지 않는다. 지점에 속한 자료(반, 학생, 수납, 패스 사용 내역)에만 두고, 지점과 무관한 자료(학부모, 할인 유형,
// 연결 테이블)에는 두지 않는다. 어느 쪽인지는 자료의 뜻으로 정하는 것이라 여기서 가릴 수 없다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class SchemaRulesTest {

	private static final Set<String> COMMON_COLUMNS = Set.of("created_at", "updated_at", "created_by");

	private static final List<String> MODULE_PREFIXES = List.of("core_", "dongtanpass_", "billing_");

	// 우리가 모양을 정하지 않는 테이블: 이벤트 발행 기록(Spring Modulith)과 마이그레이션 기록(Flyway).
	private static final Set<String> NOT_OURS = Set.of("event_publication", "flyway_schema_history");

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void 업무_테이블에는_공통_컬럼이_있다() {
		Map<String, Set<String>> tables = businessTables();

		// 하나도 못 찾았다면 읽는 방법이 틀린 것이다.
		assertThat(tables).as("업무 테이블").hasSizeGreaterThanOrEqualTo(8).containsKey("core_user");
		List<String> missing = new ArrayList<>();
		tables.forEach((table, columns) -> {
			Set<String> lacks = new TreeSet<>(COMMON_COLUMNS);
			lacks.removeAll(columns);
			if (!lacks.isEmpty()) {
				missing.add(table + "에 " + lacks + "가 없다");
			}
		});
		assertThat(missing).isEmpty();
	}

	@Test
	void 테이블_이름은_모듈의_접두사로_시작한다() {
		Set<String> unowned = new TreeSet<>(allTables().keySet());
		unowned.removeIf(table -> NOT_OURS.contains(table) || MODULE_PREFIXES.stream().anyMatch(table::startsWith));

		assertThat(unowned).as("모듈의 접두사가 없는 테이블. 직접 만든 것이 아니면 NOT_OURS에 더한다").isEmpty();
	}

	// 목록에만 남고 실제로는 없는 이름이 있으면, 그 예외는 낡은 것이다.
	@Test
	void 우리_것이_아니라고_적은_테이블은_실제로_있다() {
		assertThat(allTables().keySet()).containsAll(NOT_OURS);
	}

	private Map<String, Set<String>> businessTables() {
		Map<String, Set<String>> tables = allTables();
		tables.keySet().removeAll(NOT_OURS);
		return tables;
	}

	private Map<String, Set<String>> allTables() {
		Map<String, Set<String>> tables = new TreeMap<>();
		jdbc.query("""
				SELECT table_name, column_name FROM information_schema.columns
				WHERE table_schema = DATABASE()
				""", row -> {
			tables.computeIfAbsent(row.getString(1).toLowerCase(), table -> new TreeSet<>())
				.add(row.getString(2).toLowerCase());
		});
		return tables;
	}

}
