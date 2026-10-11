package com.jey.core.shared;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * DB에 저장하는 전화번호. 암호문, 검색용 인덱스 둘, 암호화에 쓴 키의 버전이 한 덩어리다.
 * 엔티티는 {@code @Embedded}로 갖는다. 컬럼 이름은 {@code phone_enc}, {@code phone_bidx}, {@code phone_last4_bidx},
 * {@code key_version}이고, 다른 이름이 필요하면 {@code @AttributeOverride}로 바꾼다.
 *
 * <p>만드는 것도 푸는 것도 {@link PhoneProtector}만 한다. 네 값이 항상 같이 만들어지므로 전화번호만 바뀌고 인덱스가 남는 일이 없다.
 * 전화번호를 바꿀 때는 값을 고치지 않고 새로 만들어 통째로 바꿔 끼운다.
 */
@Embeddable
public class ProtectedPhone {

	static final int CIPHERTEXT_LENGTH = 100;

	static final int INDEX_LENGTH = 64;

	// Base64(IV + 암호문 + 인증 태그). 같은 번호도 저장할 때마다 다른 값이 된다.
	@Column(name = "phone_enc", nullable = false, length = CIPHERTEXT_LENGTH)
	private String ciphertext;

	// 전체 번호의 HMAC. 같은 번호의 학부모가 이미 있는지 찾는 데 쓴다.
	@Column(name = "phone_bidx", nullable = false, length = INDEX_LENGTH)
	private String fullIndex;

	// 뒷자리 4자리의 HMAC. 데스크에서 뒷자리로 찾는 데 쓴다. 같은 뒷자리를 가진 사람이 여럿 나올 수 있다.
	@Column(name = "phone_last4_bidx", nullable = false, length = INDEX_LENGTH)
	private String last4Index;

	@Column(name = "key_version", nullable = false)
	private int keyVersion;

	protected ProtectedPhone() {
	}

	ProtectedPhone(String ciphertext, String fullIndex, String last4Index, int keyVersion) {
		this.ciphertext = ciphertext;
		this.fullIndex = fullIndex;
		this.last4Index = last4Index;
		this.keyVersion = keyVersion;
	}

	public String getCiphertext() {
		return ciphertext;
	}

	public String getFullIndex() {
		return fullIndex;
	}

	public String getLast4Index() {
		return last4Index;
	}

	public int getKeyVersion() {
		return keyVersion;
	}

	// 암호문과 인덱스도 로그에 남기지 않는다. 인덱스가 모이면 같은 번호끼리 묶을 수 있다.
	@Override
	public String toString() {
		return "ProtectedPhone[keyVersion=" + keyVersion + "]";
	}

}
