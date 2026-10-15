package com.jey.dongtanpass;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 패스 모듈 테스트의 준비.
 *
 * <p>로그인 정보의 연계 학원 식별자는 {@code @WithJeyUser}에 적는 상수라, 학원 행을 정한 ID로 넣는다.
 * 테스트 클래스마다 다른 ID 대역을 쓴다(다른 테스트가 만드는 ID와 겹치지 않게 크게 잡는다).
 */
public final class DongtanpassFixtures {

	private DongtanpassFixtures() {
	}

	/** 정한 ID의 연계 학원을 넣거나, 있으면 켜짐 여부만 바꾼다. */
	public static void upsertPartner(JdbcTemplate jdbc, long id, boolean active) {
		jdbc.update("""
				INSERT INTO dongtanpass_partner (id, name, type, active, created_at, updated_at)
				VALUES (?, ?, 'ACADEMY', ?, NOW(6), NOW(6))
				ON DUPLICATE KEY UPDATE active = VALUES(active)
				""", id, "테스트학원-" + id, active);
	}

}
