package com.jey.samplecore.alpha;

import com.jey.samplecore.alpha.inner.AlphaInner;
import com.jey.samplecore.beta.api.BetaApi;
import com.jey.samplecore.beta.api.sub.BetaDeepApi;
import com.jey.samplecore.shared.SharedThing;

// 지키는 쪽: 다른 하위 패키지는 api(와 그 아래)만, shared는 통째로, 자기 안쪽은 무엇이든.
public class AlphaService {

	BetaApi api;

	BetaDeepApi deepApi;

	SharedThing shared;

	AlphaInner inner;

}
