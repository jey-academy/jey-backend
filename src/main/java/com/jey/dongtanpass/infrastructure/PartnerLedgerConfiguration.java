package com.jey.dongtanpass.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.jey.core.sheets.api.Spreadsheets;
import com.jey.dongtanpass.application.PartnerLedgerProperties;

/**
 * 연계 학원 장부 설정. 학원의 장부를 정했는데 구글 시트 연결(키)이 없으면 서버가 뜨지 않는다.
 * 시트를 쓰지 않는 학원의 목록은 뜰 때 로그에 남긴다(잘못 넣은 학원은 미반영 목록에 드러나지 않는다).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PartnerLedgerProperties.class)
class PartnerLedgerConfiguration {

	private static final Logger log = LoggerFactory.getLogger(PartnerLedgerConfiguration.class);

	PartnerLedgerConfiguration(PartnerLedgerProperties properties, Spreadsheets sheets) {
		properties.requireSheetsIfConfigured(sheets.enabled());
		if (!properties.withoutSheet().isEmpty()) {
			log.info("시트를 쓰지 않는 연계 학원(장부 반영을 건너뛴다): {}", properties.withoutSheet().stream().sorted().toList());
		}
	}

}
