package com.jey.samplecore.gamma;

import com.jey.samplecore.alpha.AlphaService;
import com.jey.samplecore.beta.internal.BetaInternal;

// 어기는 쪽: 다른 하위 패키지의 안쪽(api가 아닌 곳)을 쓴다.
public class GammaService {

	BetaInternal betaInternal;

	AlphaService alphaService;

}
