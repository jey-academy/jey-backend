package com.jey.core.campus;

import java.math.BigDecimal;
import java.util.List;

import com.jey.core.campus.domain.DiscountType;
import com.jey.core.campus.domain.DiscountTypeRepository;
import com.jey.core.shared.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiscountTypeService {

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
		try {
			return discountTypes.saveAndFlush(created);
		}
		catch (DataIntegrityViolationException ex) {
			throw new BusinessException(CampusErrorCode.DISCOUNT_CODE_DUPLICATED, null, ex);
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
