package com.jey.dongtanpass.application;

/**
 * 연계 학원이 발급할 때 학부모에게 보여 주는 고지 문구(ADR-0035). 발급 요청은 보여 준 문구의 버전을 함께 보내야 한다.
 *
 * <p>문구를 바꾸면 버전도 바꾼다. 이미 받은 동의는 그때의 버전으로 남는다(동의 기록). 지금 문구는 임시이고, 확정되면 문구와 버전을 함께 바꾼다.
 *
 * @param version 동의 기록에 남는 값. {@link #MAX_VERSION_LENGTH}자까지
 */
public record ConsentNotice(String version, String text) {

	/** 동의 기록의 버전 칸 길이. */
	public static final int MAX_VERSION_LENGTH = 20;

	public static final ConsentNotice CURRENT = new ConsentNotice("2026-10-v1", """
			학생 이름과 학부모 휴대전화 번호를 패스 할인 확인을 위해 할인을 제공하는 어학원에 제공합니다.
			번호는 확인용 값으로만 보관하며 원래 번호로 되돌릴 수 없습니다.
			패스를 사용하거나 기간이 지나면 정해진 기간 뒤 지웁니다.""");

	/** 보낸 버전이 지금 버전인지. 화면이 예전 문구를 보여 주고 받은 동의를 걸러낸다. */
	public static boolean isCurrent(String version) {
		return CURRENT.version.equals(version);
	}

}
