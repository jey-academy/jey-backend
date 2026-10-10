package com.jey.core.config;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.UUID;

import com.jey.TestcontainersConfiguration;
import com.jey.core.shared.EventMetadata;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

// 애플리케이션이 실제로 쓰는 시계와 JSON 매퍼로 날짜·시간 규칙을 확인한다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class DateTimeRulesTest {

	@Autowired
	Clock clock;

	@Autowired
	JsonMapper mapper;

	record Sample(Instant paidAt, LocalDate issuedOn, YearMonth targetMonth) {
	}

	@Test
	void 시계는_서버_설정과_무관하게_한국_시간대다() {
		assertThat(clock.getZone()).isEqualTo(ZoneId.of("Asia/Seoul"));
	}

	// 서버가 UTC로 떠 있어도 한국 날짜가 나와야 한다. UTC 15:00은 한국 시간으로 다음 날 0시다.
	@Test
	void 오늘_날짜는_시계의_시간대로_구한다() {
		Clock atUtc1500 = Clock.fixed(Instant.parse("2026-11-02T15:00:00Z"), clock.getZone());

		assertThat(LocalDate.now(atUtc1500)).isEqualTo(LocalDate.of(2026, 11, 3));
	}

	@Test
	void 시점은_UTC_ISO_문자열로_날짜와_월은_달력_표기로_나간다() {
		Sample sample = new Sample(Instant.parse("2026-11-03T01:21:05.123Z"), LocalDate.of(2026, 11, 3),
				YearMonth.of(2026, 11));

		assertThat(mapper.writeValueAsString(sample)).isEqualTo(
				"{\"paidAt\":\"2026-11-03T01:21:05.123Z\",\"issuedOn\":\"2026-11-03\",\"targetMonth\":\"2026-11\"}");
	}

	@Test
	void 나간_형식_그대로_다시_읽는다() {
		String json = "{\"paidAt\":\"2026-11-03T01:21:05.123Z\",\"issuedOn\":\"2026-11-03\",\"targetMonth\":\"2026-11\"}";

		assertThat(mapper.readValue(json, Sample.class)).isEqualTo(new Sample(
				Instant.parse("2026-11-03T01:21:05.123Z"), LocalDate.of(2026, 11, 3), YearMonth.of(2026, 11)));
	}

	// 프론트가 +09:00이 붙은 한국 시각을 보내도 같은 시점으로 읽는다.
	@Test
	void 시간대가_붙은_시각도_같은_시점으로_읽는다() {
		String json = "{\"paidAt\":\"2026-11-03T10:21:05.123+09:00\"}";

		assertThat(mapper.readValue(json, Sample.class).paidAt()).isEqualTo(Instant.parse("2026-11-03T01:21:05.123Z"));
	}

	@Test
	void 봉투_정보는_JSON으로_나갔다가_같은_값으로_돌아온다() {
		EventMetadata metadata = new EventMetadata(UUID.fromString("0192f8a4-0000-7000-8000-000000000001"),
				"billing.payment-completed", 1, Instant.parse("2026-11-03T01:21:05.123Z"), 1L);

		String json = mapper.writeValueAsString(metadata);

		assertThat(json).isEqualTo("{\"eventId\":\"0192f8a4-0000-7000-8000-000000000001\","
				+ "\"eventType\":\"billing.payment-completed\",\"version\":1,"
				+ "\"occurredAt\":\"2026-11-03T01:21:05.123Z\",\"campusId\":1}");
		assertThat(mapper.readValue(json, EventMetadata.class)).isEqualTo(metadata);
	}

}
