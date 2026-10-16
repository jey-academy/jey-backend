package com.jey.core.auth;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * 접속 주소를 횟수 한도의 키로 바꾸는 규칙. 로그인 시도 제한과 로그인 없는 경로의 한도가 같은 규칙으로 센다.
 */
final class ClientAddresses {

	private ClientAddresses() {
	}

	/**
	 * 키에 넣을 주소. IPv6는 앞 64비트(대역)만 쓴다.
	 * IPv6 가입자는 보통 /64 대역을 통째로 받아서, 주소 전체로 세면 기기 한 대가 주소를 바꿔 가며 한도를 끝없이 새로 받는다.
	 */
	static String keyPart(String clientAddress) {
		if (clientAddress == null || clientAddress.isBlank()) {
			return "unknown";
		}
		// 콜론이 있으면 IPv6 표기다. 이름이 아니라 주소 표기만 해석하므로 DNS를 조회하지 않는다.
		if (clientAddress.indexOf(':') >= 0) {
			try {
				InetAddress address = InetAddress.getByName(clientAddress);
				if (address instanceof Inet6Address) {
					String prefix = HexFormat.of().formatHex(address.getAddress(), 0, 8);
					return prefix.substring(0, 4) + ":" + prefix.substring(4, 8) + ":" + prefix.substring(8, 12) + ":"
							+ prefix.substring(12, 16) + "::/64";
				}
				// IPv4를 IPv6로 감싼 표기(::ffff:192.0.2.1)는 IPv4로 읽힌다.
				return address.getHostAddress();
			}
			catch (UnknownHostException ex) {
				// 주소로 읽을 수 없는 값이다. 그대로 세되 키에 들어갈 수 없는 문자가 섞이지 않게 16진수로 바꾼다.
				return "invalid-" + HexFormat.of().formatHex(clientAddress.getBytes(StandardCharsets.UTF_8));
			}
		}
		return clientAddress;
	}

}
