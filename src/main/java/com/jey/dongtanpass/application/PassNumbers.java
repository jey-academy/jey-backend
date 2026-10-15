package com.jey.dongtanpass.application;

import java.security.SecureRandom;

import com.jey.dongtanpass.domain.PassNumber;
import org.springframework.stereotype.Component;

/** 새 패스 번호를 낸다. 겹치는지는 보지 않는다(저장할 때 유니크 키가 막고, 발급하는 쪽이 다시 부른다). */
@Component
class PassNumbers {

	private final SecureRandom random = new SecureRandom();

	String next() {
		return PassNumber.generate(random);
	}

}
