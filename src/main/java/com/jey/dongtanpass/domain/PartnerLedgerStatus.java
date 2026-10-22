package com.jey.dongtanpass.domain;

/**
 * 패스 사용 하나의 연계 학원 장부 반영 상태. 이름은 DB에 그대로 저장된다.
 *
 * <ul>
 * <li>{@link #PENDING} 다음 시도 시각이 되면 쓴다(처음, 장애 뒤, 관리자의 다시 반영).
 * <li>{@link #IN_PROGRESS} 한 쪽이 맡아 시트를 읽고 쓰는 중. 오래 머물면 서버가 내려간 것으로 보고 다시 맡는다.
 * <li>{@link #SYNCED} 장부에 썼다(또는 앞선 시도가 쓴 줄을 찾았다).
 * <li>{@link #UNSYNCED} 쓰지 못했다. 이유가 있고, 관리자가 고친 뒤 다시 반영한다.
 * </ul>
 */
public enum PartnerLedgerStatus {

	PENDING,
	IN_PROGRESS,
	SYNCED,
	UNSYNCED

}
