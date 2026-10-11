package com.jey.core.shared;

import java.math.BigDecimal;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * {@link Money}를 DB의 DECIMAL 컬럼 하나로 저장한다. 엔티티의 {@code Money} 필드에 자동으로 적용된다.
 * 컬럼의 소수 자릿수보다 긴 금액은 DB가 반올림하므로, 저장할 금액은 엔티티가 미리 끊어 둔다({@link Money#isWholeWon()}).
 */
@Converter(autoApply = true)
public class MoneyConverter implements AttributeConverter<Money, BigDecimal> {

	@Override
	public BigDecimal convertToDatabaseColumn(Money money) {
		return (money == null) ? null : money.amount();
	}

	@Override
	public Money convertToEntityAttribute(BigDecimal amount) {
		return (amount == null) ? null : Money.of(amount);
	}

}
