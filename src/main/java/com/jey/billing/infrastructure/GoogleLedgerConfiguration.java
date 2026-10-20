package com.jey.billing.infrastructure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.jey.billing.application.LedgerProperties;
import com.jey.billing.application.LedgerSheets;

/** 장부(구글 시트) 연결. 키가 있는데 깨졌으면 서버가 뜨지 않는다. 키가 없으면 장부 연결이 꺼진 채로 뜬다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LedgerProperties.class)
class GoogleLedgerConfiguration {

	@Bean
	GoogleAccessTokens googleAccessTokens(LedgerProperties properties) {
		return properties.hasServiceAccountKey() ? ServiceAccountAccessTokens.fromBase64(properties.serviceAccountKey())
				: GoogleAccessTokens.disabled();
	}

	@Bean
	LedgerSheets ledgerSheets(LedgerProperties properties, GoogleAccessTokens tokens) {
		return new GoogleSheetsLedgerSheets(properties.baseUrl(), properties.connectTimeout(),
				properties.readTimeout(), tokens);
	}

}
