package com.jey.core.campus;

import java.util.Optional;

import com.jey.core.campus.api.CampusCatalog;
import com.jey.core.campus.api.CampusInfo;
import com.jey.core.campus.api.ClassFee;
import com.jey.core.campus.api.ClassInfo;
import com.jey.core.campus.api.DiscountTypeInfo;
import com.jey.core.campus.domain.AcademyClassRepository;
import com.jey.core.campus.domain.CampusRepository;
import com.jey.core.campus.domain.DiscountTypeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class CampusCatalogService implements CampusCatalog {

	private final CampusRepository campuses;

	private final AcademyClassRepository classes;

	private final DiscountTypeRepository discountTypes;

	CampusCatalogService(CampusRepository campuses, AcademyClassRepository classes,
			DiscountTypeRepository discountTypes) {
		this.campuses = campuses;
		this.classes = classes;
		this.discountTypes = discountTypes;
	}

	@Override
	public boolean campusExists(long campusId) {
		return campuses.existsById(campusId);
	}

	@Override
	public Optional<CampusInfo> findCampus(long campusId) {
		return campuses.findById(campusId).map(found -> new CampusInfo(found.getId(), found.getName()));
	}

	@Override
	public Optional<ClassInfo> findClass(long classId) {
		return classes.findById(classId)
				.map(found -> new ClassInfo(found.getId(), found.getCampusId(), found.getName(), found.isActive()));
	}

	@Override
	public Optional<ClassFee> findClassFee(long classId) {
		return classes.findById(classId)
				.map(found -> new ClassFee(found.getId(), found.getCampusId(), found.getBaseFee8(),
						found.getBookFee(), found.isActive()));
	}

	@Override
	public Optional<DiscountTypeInfo> findDiscountType(String code) {
		if (code == null) {
			return Optional.empty();
		}
		return discountTypes.findByCode(code)
				.map(found -> new DiscountTypeInfo(found.getId(), found.getCode(), found.getName(), found.getRate(),
						found.isActive()));
	}

}
