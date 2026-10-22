package com.jey.dongtanpass.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.jey.core.sheets.api.Spreadsheets;
import com.jey.dongtanpass.application.PartnerLedgerProperties;

/** 연계 학원 장부 설정. 학원의 장부를 정했는데 구글 시트 연결(키)이 없으면 서버가 뜨지 않는다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PartnerLedgerProperties.class)
class PartnerLedgerConfiguration {

	PartnerLedgerConfiguration(PartnerLedgerProperties properties, Spreadsheets sheets) {
		properties.requireSheetsIfConfigured(sheets.enabled());
	}

}
