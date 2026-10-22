package com.jey.billing.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.jey.billing.application.LedgerProperties;
import com.jey.core.sheets.api.Spreadsheets;

/** 장부 설정. 지점의 장부를 정했는데 구글 시트 연결(키)이 없으면 서버가 뜨지 않는다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LedgerProperties.class)
class LedgerConfiguration {

	LedgerConfiguration(LedgerProperties properties, Spreadsheets sheets) {
		properties.requireSheetsIfConfigured(sheets.enabled());
	}

}
