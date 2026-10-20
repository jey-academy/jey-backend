package com.jey.billing.domain;

import java.time.YearMonth;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** 대상 월을 {@code 'YYYY-MM'} 글자(CHAR(7))로 저장한다. 모든 엔티티에 걸지 않고 쓰는 칸에만 붙인다. */
@Converter
class YearMonthConverter implements AttributeConverter<YearMonth, String> {

	@Override
	public String convertToDatabaseColumn(YearMonth month) {
		return month == null ? null : month.toString();
	}

	@Override
	public YearMonth convertToEntityAttribute(String text) {
		return text == null ? null : YearMonth.parse(text);
	}

}
