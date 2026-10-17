package com.jey.dongtanpass;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 패스 모듈 테스트의 준비.
 *
 * <p>로그인 정보의 연계 학원 식별자는 {@code @WithJeyUser}에 적는 상수라, 학원 행을 정한 ID로 넣는다.
 * 테스트 클래스마다 다른 ID 대역을 쓴다(다른 테스트가 만드는 ID와 겹치지 않게 크게 잡는다).
 * 장부 테스트는 자기 학원의 패스를 지우고 시작하므로 대역이 겹치면 서로의 패스를 지운다.
 * 학원과 계정 ID에 쓰는 대역(새 테스트 클래스는 비어 있는 대역을 고른다):
 * <ul>
 * <li>900_0xx {@code PartnerActiveInterceptorTest}</li>
 * <li>900_2xx {@code ConsentNoticeApiTest}, 900_3xx {@code PassIssueApiTest}, 900_4xx {@code PassIssueRetryTest}</li>
 * <li>900_5xx {@code PassLedgerApiTest}, 900_6xx {@code PassLedgerExportApiTest}</li>
 * <li>900_7xx {@code PassLinkApiTest}, 900_8xx {@code PassCancelApiTest}, 900_9xx {@code PassChangeConcurrencyTest}</li>
 * <li>901_0xx {@code HolderViewApiTest}, 901_1xx {@code HolderQrApiTest}</li>
 * <li>901_2xx {@code HolderRelinkApiTest}, 901_3xx {@code HolderRelinkServiceTest}</li>
 * <li>901_4xx {@code DeskVerifyApiTest}, 901_5xx {@code DeskVerifyConcurrencyTest}, 901_6xx {@code HolderCheckApiTest}</li>
 * <li>901_7xx {@code PassRedeemApiTest}</li>
 * </ul>
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
