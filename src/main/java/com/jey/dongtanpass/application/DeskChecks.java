package com.jey.dongtanpass.application;

import java.time.LocalDate;

import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.shared.BusinessException;
import com.jey.dongtanpass.domain.Pass;

/** 데스크 흐름(확인, 미리 확인, 사용 처리)이 함께 쓰는 확인. 같은 사정이면 같은 오류 코드가 나간다. */
final class DeskChecks {

	private DeskChecks() {
	}

	/** 패스를 받는 지점의 직원이나 관리자가 아니면 403. */
	static void requireProvidingCampus(PassBenefit benefit, AuthenticatedUser user) {
		if (!user.campusScope().includes(benefit.campusId())) {
			throw new BusinessException(DongtanpassErrorCode.CAMPUS_NOT_PROVIDING);
		}
	}

	/** 쓸 수 있는 패스(발급됨, 기간 안)가 아니면 그 사정의 409. */
	static void requireUsable(Pass pass, LocalDate today) {
		DongtanpassErrorCode unusable = switch (pass.stateOn(today)) {
			case ISSUED -> null;
			case EXPIRED -> DongtanpassErrorCode.PASS_EXPIRED;
			case CANCELED -> DongtanpassErrorCode.PASS_CANCELED;
			case REDEEMED -> DongtanpassErrorCode.PASS_ALREADY_REDEEMED;
		};
		if (unusable != null) {
			throw new BusinessException(unusable);
		}
	}

}
