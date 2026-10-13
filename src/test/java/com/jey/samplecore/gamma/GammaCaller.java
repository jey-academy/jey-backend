package com.jey.samplecore.gamma;

import com.jey.samplecore.beta.internal.BetaInternal;

// 어기는 쪽: 필드로 갖지 않고 메서드만 불러도 다른 하위 패키지의 안쪽을 쓰는 것이다.
public class GammaCaller {

	void run() {
		BetaInternal.touch();
	}

}
