package com.jey.samplerules;

// 어기는 쪽: 생성자를 직접 부른다.
public class CallsConstructor {

	Guarded make() {
		return new Guarded("by-hand", 2);
	}

}
