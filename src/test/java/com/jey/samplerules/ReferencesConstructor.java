package com.jey.samplerules;

// 어기는 쪽: 생성자를 부르지 않고 가리킨다. 가리킨 것을 누가 부르든 팩터리를 거치지 않는다.
public class ReferencesConstructor {

	Guarded make() {
		Guarded.Maker maker = Guarded::new;
		return maker.make("by-reference", 2);
	}

}
