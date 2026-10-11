package com.jey.core.shared;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 전화번호를 DB에 저장할 형태로 바꾸고({@link #protect}), 되돌리고({@link #reveal}), 검색용 인덱스를 만든다.
 *
 * <p>암호문은 AES-256-GCM이다. 키 없이는 읽을 수 없고, 값이 바뀌면 되돌릴 때 실패한다.
 * 암호문으로는 찾을 수 없으므로(같은 번호도 매번 다른 암호문이 된다) 검색은 인덱스로 한다.
 * 인덱스는 번호에 비밀키를 섞은 해시(HMAC-SHA256)라서, 같은 번호는 항상 같은 값이 되고 키 없이는 번호를 되짚을 수 없다.
 *
 * <p>인덱스의 안전은 HMAC 키에 달려 있다. 뒷자리는 1만 가지뿐이라 키가 새면 전부 대입해서 알아낼 수 있다.
 * 그래서 HMAC 키를 암호화 키와 따로 둔다(ADR-0009).
 */
@Component
@EnableConfigurationProperties(PhoneCryptoProperties.class)
public class PhoneProtector {

	private static final String CIPHER = "AES/GCM/NoPadding";

	private static final String HMAC = "HmacSHA256";

	private static final int IV_BYTES = 12;

	private static final int TAG_BITS = 128;

	private static final Pattern LAST4 = Pattern.compile("\\d{4}");

	private final SecureRandom random = new SecureRandom();

	private final int activeKeyVersion;

	private final Map<Integer, SecretKey> encryptionKeys = new HashMap<>();

	private final SecretKey hmacKey;

	PhoneProtector(PhoneCryptoProperties properties) {
		this.activeKeyVersion = properties.activeKeyVersion();
		properties.encryptionKeys()
				.keySet()
				.forEach(version -> this.encryptionKeys.put(version,
						new SecretKeySpec(properties.encryptionKeyBytes(version), "AES")));
		this.hmacKey = new SecretKeySpec(properties.hmacKeyBytes(), HMAC);
	}

	/** 저장할 형태로 바꾼다. 지금 쓰는 버전의 키로 암호화한다. */
	public ProtectedPhone protect(PhoneNumber phone) {
		Objects.requireNonNull(phone, "phone");
		return new ProtectedPhone(encrypt(phone.value(), activeKeyVersion), fullIndex(phone),
				last4Index(phone.last4()), activeKeyVersion);
	}

	/**
	 * 원래 번호로 되돌린다. 원문이 필요한 곳(알림 발송, 전체 번호 보기)에서만 부른다.
	 *
	 * @throws IllegalStateException 그 버전의 키가 없거나, 키가 다르거나, 저장된 값이 바뀌었거나,
	 * 되돌린 번호가 함께 저장된 인덱스와 맞지 않을 때. 값을 읽지 못한 것을 빈 값으로 넘기지 않는다
	 */
	public PhoneNumber reveal(ProtectedPhone phone) {
		Objects.requireNonNull(phone, "phone");
		PhoneNumber revealed = PhoneNumber.of(decrypt(phone.getCiphertext(), phone.getKeyVersion()));
		// 암호문에 묶인 것은 키 버전뿐이라, 같은 버전인 다른 행의 암호문도 복호화는 된다.
		// 되돌린 번호로 인덱스를 다시 계산해 이 행의 것과 맞는지 본다.
		// 네 값을 통째로 옮긴 경우는 여기서 잡지 못한다(ADR-0009).
		if (!sameIndex(fullIndex(revealed), phone.getFullIndex())
				|| !sameIndex(last4Index(revealed.last4()), phone.getLast4Index())) {
			throw new IllegalStateException(
					"전화번호의 암호문과 인덱스가 맞지 않는다(키 버전 " + phone.getKeyVersion() + ")");
		}
		return revealed;
	}

	/** 전체 번호로 찾을 때 쓰는 인덱스. {@code phone_bidx}와 비교한다. */
	public String fullIndex(PhoneNumber phone) {
		Objects.requireNonNull(phone, "phone");
		return hmac("full:" + phone.value());
	}

	/**
	 * 뒷자리 4자리로 찾을 때 쓰는 인덱스. {@code phone_last4_bidx}와 비교한다.
	 * 같은 뒷자리를 가진 사람이 여럿 나올 수 있으므로 결과는 후보 목록이다.
	 *
	 * @throws IllegalArgumentException 숫자 4자리가 아닐 때
	 */
	public String last4Index(String last4) {
		if (last4 == null || !LAST4.matcher(last4).matches()) {
			throw new IllegalArgumentException("뒷자리는 숫자 4자리여야 한다");
		}
		// 전체 번호와 뒷자리에 다른 머리말을 붙여서, 두 인덱스의 값이 서로 섞이지 않게 한다.
		return hmac("last4:" + last4);
	}

	private String encrypt(String plain, int keyVersion) {
		// 같은 키로 같은 IV를 두 번 쓰면 GCM은 깨진다. 매번 새로 만든다.
		byte[] iv = new byte[IV_BYTES];
		random.nextBytes(iv);
		try {
			// Cipher는 여러 스레드가 같이 쓸 수 없어 매번 새로 얻는다.
			Cipher cipher = Cipher.getInstance(CIPHER);
			cipher.init(Cipher.ENCRYPT_MODE, encryptionKeys.get(keyVersion), new GCMParameterSpec(TAG_BITS, iv));
			cipher.updateAAD(associatedData(keyVersion));
			byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
			return Base64.getEncoder()
					.encodeToString(ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("전화번호를 암호화할 수 없다", ex);
		}
	}

	// 예외 문구에 암호문이나 번호를 싣지 않는다.
	private String decrypt(String ciphertext, int keyVersion) {
		SecretKey key = encryptionKeys.get(keyVersion);
		if (key == null) {
			throw new IllegalStateException("전화번호를 암호화한 키(버전 " + keyVersion + ")가 설정에 없다");
		}
		byte[] bytes;
		try {
			bytes = Base64.getDecoder().decode(ciphertext);
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException("저장된 전화번호의 형식이 잘못됐다(키 버전 " + keyVersion + ")");
		}
		if (bytes.length <= IV_BYTES) {
			throw new IllegalStateException("저장된 전화번호의 형식이 잘못됐다(키 버전 " + keyVersion + ")");
		}
		try {
			Cipher cipher = Cipher.getInstance(CIPHER);
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES));
			cipher.updateAAD(associatedData(keyVersion));
			return new String(cipher.doFinal(bytes, IV_BYTES, bytes.length - IV_BYTES), StandardCharsets.UTF_8);
		}
		catch (AEADBadTagException ex) {
			throw new IllegalStateException("전화번호를 되돌릴 수 없다. 키가 다르거나 저장된 값이 바뀌었다(키 버전 " + keyVersion + ")", ex);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("전화번호를 되돌릴 수 없다(키 버전 " + keyVersion + ")", ex);
		}
	}

	// 암호문에 묶어 두는 값. 암호문을 다른 키 버전의 행에 옮겨 붙이면 되돌릴 때 실패한다.
	private static byte[] associatedData(int keyVersion) {
		return ("phone:v" + keyVersion).getBytes(StandardCharsets.UTF_8);
	}

	private static boolean sameIndex(String computed, String stored) {
		return stored != null && MessageDigest.isEqual(computed.getBytes(StandardCharsets.UTF_8),
				stored.getBytes(StandardCharsets.UTF_8));
	}

	private String hmac(String value) {
		try {
			// Mac은 여러 스레드가 같이 쓸 수 없어 매번 새로 얻는다.
			Mac mac = Mac.getInstance(HMAC);
			mac.init(hmacKey);
			return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("전화번호의 인덱스를 계산할 수 없다", ex);
		}
	}

}
