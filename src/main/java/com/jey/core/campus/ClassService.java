package com.jey.core.campus;

import java.util.List;

import com.jey.core.campus.api.ClassInfo;
import com.jey.core.campus.domain.AcademyClass;
import com.jey.core.campus.domain.AcademyClassRepository;
import com.jey.core.campus.domain.CampusRepository;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.Money;
import com.jey.core.shared.UniqueKeys;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 반과 수강료표. 수강료는 관리자만 본다.
 * 직원도 부르는 {@link #activeClasses}는 수강료가 없는 {@link ClassInfo}를 돌려준다.
 * 그 밖의 메서드가 돌려주는 {@link AcademyClass}에는 수강료가 들어 있고, 관리자 전용 기능만 부른다.
 */
@Service
public class ClassService {

	private static final String NAME_UNIQUE_KEY = "uk_core_class_campus_name";

	private final AcademyClassRepository classes;

	private final CampusRepository campuses;

	ClassService(AcademyClassRepository classes, CampusRepository campuses) {
		this.classes = classes;
		this.campuses = campuses;
	}

	/**
	 * 새로 고를 수 있는 반(운영 중인 반). 수강료는 담지 않는다.
	 *
	 * @param campusId 지점. {@code null}이면 전 지점
	 */
	@Transactional(readOnly = true)
	public List<ClassInfo> activeClasses(Long campusId) {
		List<AcademyClass> found = (campusId == null) ? classes.findByActiveTrueOrderByCampusIdAscNameAsc()
				: classes.findByCampusIdAndActiveTrueOrderByNameAsc(campusId);
		return found.stream()
				.map(each -> new ClassInfo(each.getId(), each.getCampusId(), each.getName(), each.isActive()))
				.toList();
	}

	/**
	 * 꺼진 반까지 포함한 전체. 수강료표 관리에 쓴다.
	 *
	 * @param campusId 지점. {@code null}이면 전 지점
	 */
	@Transactional(readOnly = true)
	public List<AcademyClass> allClasses(Long campusId) {
		return (campusId == null) ? classes.findAllByOrderByCampusIdAscNameAsc()
				: classes.findByCampusIdOrderByNameAsc(campusId);
	}

	/**
	 * @throws BusinessException 없는 지점이거나, 금액이 허용 범위 밖이거나, 그 지점에 같은 이름의 반이 있을 때
	 */
	@Transactional
	public AcademyClass create(long campusId, String name, Money baseFee8, Money bookFee) {
		if (!campuses.existsById(campusId)) {
			throw new BusinessException(CampusErrorCode.INVALID_CAMPUS);
		}
		requireValidFees(baseFee8, bookFee);
		AcademyClass created = AcademyClass.create(campusId, name, baseFee8, bookFee);
		if (classes.existsByCampusIdAndName(campusId, created.getName())) {
			throw new BusinessException(CampusErrorCode.CLASS_NAME_DUPLICATED);
		}
		return saveOrDuplicated(created);
	}

	/**
	 * @throws BusinessException 없는 반이거나, 금액이 허용 범위 밖이거나, 그 지점에 같은 이름의 다른 반이 있을 때
	 */
	@Transactional
	public AcademyClass update(long id, String name, Money baseFee8, Money bookFee, boolean active) {
		AcademyClass found = classes.findById(id)
				.orElseThrow(() -> new BusinessException(CampusErrorCode.CLASS_NOT_FOUND));
		requireValidFees(baseFee8, bookFee);
		// 값을 바꾸기 전에 확인한다. 바꾼 뒤에 조회하면 조회 직전에 변경이 DB로 나가서 유니크 키 오류가 여기서 먼저 난다.
		if (name != null && classes.existsByCampusIdAndNameAndIdNot(found.getCampusId(), name.strip(), id)) {
			throw new BusinessException(CampusErrorCode.CLASS_NAME_DUPLICATED);
		}
		found.update(name, baseFee8, bookFee, active);
		return saveOrDuplicated(found);
	}

	private static void requireValidFees(Money baseFee8, Money bookFee) {
		if (!AcademyClass.isValidBaseFee(baseFee8) || !AcademyClass.isValidBookFee(bookFee)) {
			throw new BusinessException(CampusErrorCode.INVALID_FEE);
		}
	}

	// 위의 이름 확인과 저장 사이에 같은 이름이 먼저 들어오면 DB의 유니크 키가 막는다. 그 경우도 같은 오류로 답한다.
	// 다른 제약 위반은 중복이 아니므로 바꾸지 않고 그대로 던진다(500으로 로그에 남는다).
	private AcademyClass saveOrDuplicated(AcademyClass academyClass) {
		try {
			return classes.saveAndFlush(academyClass);
		}
		catch (DataIntegrityViolationException ex) {
			if (UniqueKeys.isViolationOf(ex, NAME_UNIQUE_KEY)) {
				throw new BusinessException(CampusErrorCode.CLASS_NAME_DUPLICATED, null, ex);
			}
			throw ex;
		}
	}

}
