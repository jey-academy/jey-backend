package com.jey.dongtanpass.api;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;

import com.jey.core.shared.Actor;
import com.jey.core.shared.EventMetadata;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CampusPassIssuedTest {

	// 이벤트 이름은 감사 로그의 행위 이름이 되고, 미완료 발행 기록에도 적혀 있다. 바꾸지 않는다.
	@Test
	void 이벤트_이름과_대상은_정해져_있다() {
		CampusPassIssued event = new CampusPassIssued(EventMetadata.create(CampusPassIssued.TYPE,
				CampusPassIssued.VERSION, 1L, Actor.none(),
				Clock.fixed(Instant.parse("2026-10-20T01:00:00Z"), ZoneId.of("Asia/Seoul"))), 7L, 1L, 42L);

		assertThat(CampusPassIssued.TYPE).isEqualTo("dongtanpass.campus-pass-issued");
		assertThat(event.subject()).contains(PassRefs.pass(7));
		assertThat(event.metadata().campusId()).isEqualTo(1L);
	}

	// 이벤트에는 개인정보(학생 이름, 학부모 번호)와 링크 토큰을 싣지 않는다. ID만 싣는다.
	@Test
	void 이벤트의_칸은_ID뿐이다() {
		assertThat(Arrays.stream(CampusPassIssued.class.getRecordComponents()).map(c -> c.getName()))
			.containsExactly("metadata", "passId", "campusId", "studentId");
	}

}
