package com.jey.samplerules;

// 지키는 쪽: 팩터리로 만들고, 만들어진 값을 읽기만 한다.
public class UsesFactory {

	String make() {
		Guarded guarded = Guarded.of("by-factory");
		return guarded.name() + guarded.count();
	}

}
