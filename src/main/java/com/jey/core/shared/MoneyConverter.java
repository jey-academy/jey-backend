package com.jey.core.shared;

import java.math.BigDecimal;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * {@link Money}를 DB의 DECIMAL 컬럼 하나로 저장한다. 엔티티의 {@code Money} 필드에 자동으로 적용된다.
 * 1원 미만이 남은 금액은 저장하지 않는다. 컬럼이 오류 없이 반올림해서, 버림으로 끊어야 할 금액이 올림된 채 저장되기 때문이다.
 * 저장할 금액은 엔티티가 {@link Money#round}나 {@link Money#dividedBy}로 미리 끊어 둔다.
 */
@Converter(autoApply = true)
public class MoneyConverter implements AttributeConverter<Money, BigDecimal> {

	/**
	 * @throws IllegalStateException 1원 미만이 남은 금액일 때
	 */
	@Override
	public BigDecimal convertToDatabaseColumn(Money money) {
		if (money == null) {
			return null;
		}
		if (!money.isWholeWon()) {
			throw new IllegalStateException("1원 미만이 남은 금액은 저장할 수 없다. 저장하기 전에 끊는다");
		}
		return money.amount();
	}

	@Override
	public Money convertToEntityAttribute(BigDecimal amount) {
		return (amount == null) ? null : Money.of(amount);
	}

}
