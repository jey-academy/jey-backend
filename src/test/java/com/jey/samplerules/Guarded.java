package com.jey.samplerules;

// 팩터리로만 만들어야 하는 타입의 본보기. record라서 생성자를 감출 수 없다.
public record Guarded(String name, int count) {

	public static Guarded of(String name) {
		return new Guarded(name, 1);
	}

	// 자기 안에서 생성자를 가리키는 것은 괜찮다.
	public static Maker maker() {
		return Guarded::new;
	}

	public interface Maker {

		Guarded make(String name, int count);

	}

}
