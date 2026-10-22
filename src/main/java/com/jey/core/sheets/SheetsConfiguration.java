package com.jey.core.sheets;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.jey.core.sheets.api.Spreadsheets;

/** 구글 시트 연결. 키가 있는데 깨졌으면 서버가 뜨지 않는다. 키가 없으면 연결이 꺼진 채로 뜬다({@link Spreadsheets#enabled()}). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SheetsProperties.class)
class SheetsConfiguration {

	@Bean
	Spreadsheets spreadsheets(SheetsProperties properties) {
		GoogleAccessTokens tokens = properties.hasServiceAccountKey()
				? ServiceAccountAccessTokens.fromBase64(properties.serviceAccountKey()) : GoogleAccessTokens.disabled();
		return new GoogleSpreadsheets(properties.baseUrl(), properties.connectTimeout(), properties.readTimeout(), tokens);
	}

}
