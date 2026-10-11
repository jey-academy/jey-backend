package com.jey.core.campus;

import java.math.BigDecimal;
import java.util.List;

import com.jey.core.campus.domain.DiscountType;
import com.jey.core.campus.domain.DiscountTypeRepository;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.UniqueKeys;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiscountTypeService {

	private static final String CODE_UNIQUE_KEY = "uk_core_discount_type_code";

	private final DiscountTypeRepository discountTypes;

	DiscountTypeService(DiscountTypeRepository discountTypes) {
		this.discountTypes = discountTypes;
	}

	/** 꺼진 것까지 포함한 전체. */
	@Transactional(readOnly = true)
	public List<DiscountType> all() {
		return discountTypes.findAllByOrderByIdAsc();
	}

	/**
	 * @throws BusinessException 같은 코드의 할인 유형이 있을 때
	 */
	@Transactional
	public DiscountType create(String code, String name, BigDecimal rate) {
		DiscountType created = DiscountType.create(code, name, rate);
		if (discountTypes.existsByCode(code)) {
			throw new BusinessException(CampusErrorCode.DISCOUNT_CODE_DUPLICATED);
		}
		// 위의 확인과 저장 사이에 같은 코드가 먼저 들어오면 DB의 유니크 키가 막는다. 그 경우도 같은 오류로 답한다.
		// 다른 제약 위반은 중복이 아니므로 바꾸지 않고 그대로 던진다(500으로 로그에 남는다).
		try {
			return discountTypes.saveAndFlush(created);
		}
		catch (DataIntegrityViolationException ex) {
			if (UniqueKeys.isViolationOf(ex, CODE_UNIQUE_KEY)) {
				throw new BusinessException(CampusErrorCode.DISCOUNT_CODE_DUPLICATED, null, ex);
			}
			throw ex;
		}
	}

	/**
	 * 코드는 바꾸지 않는다.
	 *
	 * @throws BusinessException 없는 할인 유형일 때
	 */
	@Transactional
	public DiscountType update(long id, String name, BigDecimal rate, boolean active) {
		DiscountType found = discountTypes.findById(id)
				.orElseThrow(() -> new BusinessException(CampusErrorCode.DISCOUNT_TYPE_NOT_FOUND));
		found.update(name, rate, active);
		return discountTypes.saveAndFlush(found);
	}

}
