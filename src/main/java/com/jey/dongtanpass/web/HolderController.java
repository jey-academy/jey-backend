package com.jey.dongtanpass.web;

import com.jey.core.auth.api.PublicEndpoint;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.CommonErrorCode;
import com.jey.core.shared.NotIdempotent;
import com.jey.dongtanpass.application.HolderPassService;
import com.jey.dongtanpass.application.HolderRelinkService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 학부모는 로그인하지 않는다. 링크 토큰이 열쇠다(본문으로 받는다). 보안 설정의 허용 경로에도 적혀 있다.
// 로그인이 없어 멱등 키를 계정별로 볼 수 없다. CSRF 토큰은 필요하다.
@RestController
@RequestMapping("/api/v1/dongtanpass/holder")
class HolderController {

	private static final Logger log = LoggerFactory.getLogger(HolderController.class);

	private final HolderPassService holderPassService;

	private final HolderRelinkService holderRelinkService;

	HolderController(HolderPassService holderPassService, HolderRelinkService holderRelinkService) {
		this.holderPassService = holderPassService;
		this.holderRelinkService = holderRelinkService;
	}

	@Operation(summary = "학부모 패스 열기",
			description = "링크 토큰으로 패스를 연다. 로그인 없이 부르고 CSRF 토큰은 싣는다. 없거나 바뀐 링크는 404(DONGTANPASS_PASS_NOT_FOUND) 하나로 답한다")
	@PublicEndpoint
	@NotIdempotent("로그인하지 않은 경로라 키를 계정별로 볼 수 없다. 바꾸는 자료가 없다")
	@PostMapping("/view")
	HolderPassResponse viewHolderPass(@Valid @RequestBody HolderTokenRequest body) {
		return HolderPassResponse.from(holderPassService.view(body.token()));
	}

	@Operation(summary = "학부모 QR 받기",
			description = "할인을 주는 곳(연계 학원 발급분은 JEY 데스크, JEY 발급분은 연계 학원)에 보여 줄 QR 토큰을 준다. 약 60초(59초 넘게 60초 이하) 쓸 수 있고 부를 때마다 새 토큰이다. 화면은 expiresAt 전에 다시 받는다. "
					+ "쓸 수 있는 패스만 받는다(기간이 지났거나 취소됐거나 사용됐으면 409)")
	@PublicEndpoint
	@NotIdempotent("로그인하지 않은 경로라 키를 계정별로 볼 수 없다. 부를 때마다 새 QR 토큰을 준다")
	@PostMapping("/qr")
	HolderQrResponse issueHolderQr(@Valid @RequestBody HolderTokenRequest body) {
		return HolderQrResponse.from(holderPassService.qr(body.token()));
	}

	@Operation(summary = "학부모 링크 다시 받기",
			description = "링크를 잃어버린 학부모가 학생 이름과 패스에 등록된 학부모 번호(전체. 연계 학원 발급분은 발급 때 적은 번호, JEY 발급분은 학생의 대표 연락처)로 새 링크를 받는다. 로그인 없이 부르고 CSRF 토큰은 싣는다. "
					+ "이름은 띄어쓰기와 무관하고 번호는 하이픈과 무관하다. 맞는 패스 중 쓸 수 있는 것(발급됨, 기간 안)이 여럿이면 가장 최근에 발급된 하나의 링크를 새로 만든다. "
					+ "그 패스의 예전 링크는 바로 막힌다(다른 보호자가 갖고 있던 링크도). "
					+ "이름이나 번호가 맞지 않으면 어느 쪽인지 알리지 않고 404(DONGTANPASS_PASS_NOT_FOUND) 하나로 답한다. "
					+ "맞았지만 쓸 수 있는 패스가 없으면 가장 최근 패스의 사정으로 409(DONGTANPASS_PASS_ALREADY_REDEEMED, DONGTANPASS_PASS_EXPIRED, DONGTANPASS_PASS_CANCELED). "
					+ "맞지 않은 요청이 같은 접속 주소(같은 와이파이 등)에서 한 번호로, 또는 번호와 무관하게 정해진 횟수를 넘으면 잠시 429(DONGTANPASS_HOLDER_RELINK_LIMITED)이다. "
					+ "같은 와이파이를 쓰는 다른 사람의 시도로 막힐 수도 있다. 다시 할 수 있을 때까지 남은 시간은 "
					+ "Retry-After 헤더(초)에 싣는다. 이름과 번호는 주소가 아니라 본문으로만 보낸다(쿼리 문자열이 있으면 400)")
	@PublicEndpoint
	@NotIdempotent("로그인하지 않은 경로라 키를 계정별로 볼 수 없다. 다시 보내면 링크를 한 번 더 새로 만들고, 앞서 받은 링크는 막힌다")
	@PostMapping("/relink")
	HolderRelinkResponse relinkHolderPass(@Valid @RequestBody HolderRelinkRequest body, HttpServletRequest request) {
		// 이름과 번호를 주소에 실어 보낸 요청이다. 읽지 않고 넘기면 주소에 실린 값이 앞단의 접근 로그에 남은 것을 아무도 모른다.
		// 거부해서 드러나게 한다. 이름이나 값은 로그에 남기지 않는다.
		if (!request.getParameterMap().isEmpty()) {
			log.warn("학부모 링크 다시 받기에 쿼리 문자열이 실려 왔다. 이름과 번호는 본문으로만 받는다: 파라미터 {}개",
					request.getParameterMap().size());
			throw new BusinessException(CommonErrorCode.BAD_REQUEST, "이름과 번호는 주소가 아니라 본문으로 보내야 합니다.");
		}
		return HolderRelinkResponse.from(
				holderRelinkService.relink(body.studentName(), body.guardianPhone(), request.getRemoteAddr()));
	}

}
