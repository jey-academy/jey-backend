package com.jey.core.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClientAddressesTest {

	@Test
	void 키에_넣는_주소() {
		assertThat(ClientAddresses.keyPart("203.0.113.7")).isEqualTo("203.0.113.7");
		assertThat(ClientAddresses.keyPart("2001:db8:12:3400:1:2:3:4")).isEqualTo("2001:0db8:0012:3400::/64");
		assertThat(ClientAddresses.keyPart("2001:DB8:12:3400::ffff")).isEqualTo("2001:0db8:0012:3400::/64");
		assertThat(ClientAddresses.keyPart("0:0:0:0:0:0:0:1")).isEqualTo("0000:0000:0000:0000::/64");
		// IPv4를 IPv6로 감싼 표기는 IPv4와 같은 곳이다.
		assertThat(ClientAddresses.keyPart("::ffff:203.0.113.7")).isEqualTo("203.0.113.7");
		assertThat(ClientAddresses.keyPart(null)).isEqualTo("unknown");
		assertThat(ClientAddresses.keyPart(" ")).isEqualTo("unknown");
		// 주소로 읽을 수 없는 값도 세기는 한다. 키에는 원문 대신 해시가 들어간다.
		assertThat(ClientAddresses.keyPart("not:an:address")).matches("invalid-[0-9a-f]+");
	}

}
