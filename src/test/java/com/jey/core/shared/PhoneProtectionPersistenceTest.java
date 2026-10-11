package com.jey.core.shared;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jey.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

// 전화번호를 가진 테이블은 아직 없다(학부모 테이블은 뒤 작업에서 만든다).
// 여기서는 테스트용 테이블에 같은 컬럼을 두고, 실제 DB에서 원문이 남지 않는지와 인덱스로 찾아지는지를 본다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class PhoneProtectionPersistenceTest {

	private static final Logger log = LoggerFactory.getLogger(PhoneProtectionPersistenceTest.class);

	private final String table = "test_phone_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

	@Autowired
	PhoneProtector protector;

	@Autowired
	JdbcTemplate jdbc;

	@BeforeEach
	void createTable() {
		jdbc.execute("CREATE TABLE " + table + " (id BIGINT NOT NULL AUTO_INCREMENT, name VARCHAR(50) NOT NULL, "
				+ "phone_enc VARCHAR(100) NOT NULL, phone_bidx VARCHAR(64) NOT NULL, "
				+ "phone_last4_bidx VARCHAR(64) NOT NULL, key_version INT NOT NULL, PRIMARY KEY (id), "
				+ "KEY idx_bidx (phone_bidx), KEY idx_last4 (phone_last4_bidx))");
	}

	@AfterEach
	void dropTable() {
		jdbc.execute("DROP TABLE IF EXISTS " + table);
	}

	// 컨텍스트가 떴다는 것은 테스트 설정의 키가 유효하고 빈이 연결됐다는 뜻이다.
	@Test
	void DB에_저장된_값_어디에도_원문이_없다() {
		insert("김학부모", PhoneNumber.of("010-9876-5432"));

		Map<String, Object> row = jdbc.queryForMap("SELECT * FROM " + table);

		// 4자리만으로 확인하면 암호문이나 인덱스에 우연히 같은 숫자가 나올 때 실패한다. 8자리로 본다.
		assertThat(row.values().toString()).doesNotContain("01098765432").doesNotContain("98765432");
	}

	@Test
	void 뒷자리로_찾으면_같은_뒷자리의_사람이_모두_나온다() {
		insert("김학부모", PhoneNumber.of("010-9876-5432"));
		insert("이학부모", PhoneNumber.of("010-1111-5432"));
		insert("박학부모", PhoneNumber.of("010-9876-0000"));

		List<String> found = jdbc.queryForList("SELECT name FROM " + table + " WHERE phone_last4_bidx = ? ORDER BY id",
				String.class, protector.last4Index("5432"));

		// 뒷자리 검색은 본인 확인이 아니라 후보 목록이다.
		assertThat(found).containsExactly("김학부모", "이학부모");
		assertThat(jdbc.queryForList("SELECT name FROM " + table + " WHERE phone_last4_bidx = ?", String.class,
				protector.last4Index("9999")))
				.isEmpty();
	}

	// 같은 번호의 학부모가 이미 있는지 찾는 데 쓴다. 적는 방식이 달라도 찾아져야 한다.
	@Test
	void 전체_번호로_찾으면_그_사람만_나온다() {
		insert("김학부모", PhoneNumber.of("010-9876-5432"));
		insert("이학부모", PhoneNumber.of("010-1111-5432"));

		List<String> found = jdbc.queryForList("SELECT name FROM " + table + " WHERE phone_bidx = ?", String.class,
				protector.fullIndex(PhoneNumber.of("+82 10 9876 5432")));

		assertThat(found).containsExactly("김학부모");
	}

	@Test
	void DB에서_읽은_값을_되돌리면_원래_번호다() {
		insert("김학부모", PhoneNumber.of("010-9876-5432"));

		ProtectedPhone read = jdbc.queryForObject(
				"SELECT phone_enc, phone_bidx, phone_last4_bidx, key_version FROM " + table,
				(rs, rowNum) -> new ProtectedPhone(rs.getString("phone_enc"), rs.getString("phone_bidx"),
						rs.getString("phone_last4_bidx"), rs.getInt("key_version")));

		assertThat(protector.reveal(read).value()).isEqualTo("01098765432");
	}

	// 디버깅하다 객체를 그대로 찍는 일은 흔하다. 그래도 원문, 암호문, 인덱스가 로그에 남지 않아야 한다.
	@Test
	void 전화번호와_보호한_값을_로그에_찍어도_원문이_남지_않는다(CapturedOutput output) {
		PhoneNumber phone = PhoneNumber.of("010-9876-5432");
		ProtectedPhone stored = protector.protect(phone);

		log.info("학부모 연락처 저장: phone={} stored={}", phone, stored);
		insert("김학부모", phone);

		assertThat(output.getAll()).contains("phone=010-****-5432")
				.contains("stored=ProtectedPhone[keyVersion=1]")
				.doesNotContain("01098765432")
				.doesNotContain("98765432")
				.doesNotContain(stored.getCiphertext())
				.doesNotContain(stored.getFullIndex())
				.doesNotContain(stored.getLast4Index());
	}

	private void insert(String name, PhoneNumber phone) {
		ProtectedPhone stored = protector.protect(phone);
		jdbc.update("INSERT INTO " + table + " (name, phone_enc, phone_bidx, phone_last4_bidx, key_version) "
				+ "VALUES (?, ?, ?, ?, ?)", name, stored.getCiphertext(), stored.getFullIndex(), stored.getLast4Index(),
				stored.getKeyVersion());
	}

}
