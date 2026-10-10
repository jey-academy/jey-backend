package com.jey.core.auth.api;

/**
 * 소속 지점으로 묶은 자료에서 한 사용자가 다룰 수 있는 지점. {@link AuthenticatedUser#campusScope()}로 얻는다.
 *
 * <p>모든 자료에 쓰는 것이 아니다. 직원은 기본적으로 전 지점의 자료를 다루고, 묶기로 정한 자료(수납, 수강료표, 패스 사용 내역)에만 쓴다.
 * 조회 조건을 만들 때는 두 경우를 모두 다룬다.
 *
 * <pre>{@code
 * return switch (user.campusScopeFor(requestedCampusId)) {
 *     case CampusScope.All all -> invoices.findAll(pageable);
 *     case CampusScope.Only only -> invoices.findByCampusId(only.campusId(), pageable);
 * };
 * }</pre>
 */
public sealed interface CampusScope {

	boolean includes(long campusId);

	/** 전 지점. */
	record All() implements CampusScope {

		@Override
		public boolean includes(long campusId) {
			return true;
		}

	}

	/** 한 지점. */
	record Only(long campusId) implements CampusScope {

		@Override
		public boolean includes(long campusId) {
			return this.campusId == campusId;
		}

	}

}
