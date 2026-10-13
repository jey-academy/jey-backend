package com.jey.samplecore.shared;

import com.jey.samplecore.beta.api.BetaApi;

// 어기는 쪽: shared는 누구나 쓰는 바닥이다. 다른 하위 패키지는 api라도 쓰지 않는다.
public class SharedViolator {

	BetaApi api;

}
