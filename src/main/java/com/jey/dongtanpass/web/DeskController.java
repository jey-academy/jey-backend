package com.jey.dongtanpass.web;

import com.jey.core.auth.api.AllowedRoles;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.CommonErrorCode;
import com.jey.core.shared.Idempotent;
import com.jey.core.shared.NotIdempotent;
import com.jey.dongtanpass.application.DeskPassService;
import com.jey.dongtanpass.application.PassRedeemService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// 데스크 직원이 학부모의 패스를 확인한다. 패스를 받는 지점인지는 서비스가 본다(다른 지점 직원은 403).
@RestController
@RequestMapping("/api/v1/dongtanpass/passes")
@AllowedRoles({ UserRole.STAFF, UserRole.ADMIN })
class DeskController {

	private static final Logger log = LoggerFactory.getLogger(DeskController.class);

	private final DeskPassService deskPassService;

	private final PassRedeemService passRedeemService;

	DeskController(DeskPassService deskPassService, PassRedeemService passRedeemService) {
		this.deskPassService = deskPassService;
		this.passRedeemService = passRedeemService;
	}

	@Operation(summary = "데스크 패스 확인",
			description = "학부모 화면의 QR에서 읽은 토큰(qrToken)이나 학부모가 불러 주는 패스 번호(passNo) 중 하나로 패스를 확인하고, "
					+ "사용 처리에 쓸 패스 ID를 준다. 둘 다 보내거나 둘 다 없으면 400(COMMON_BAD_REQUEST). "
					+ "QR: 한 토큰은 한 번만 통과한다. 토큰이 틀렸거나 만료됐거나 이미 쓴 것이면 이유를 가리지 않고 400(DONGTANPASS_QR_INVALID) 하나다. "
					+ "학부모 화면에서 QR을 새로 받아 다시 읽는다. 쓸 수 없는 패스여도 토큰은 쓴 것이다. "
					+ "패스 번호: 형식(대문자, 예: DT-ABC234)이 틀리면 그 칸의 400, 없는 번호는 404(DONGTANPASS_PASS_NOT_FOUND). "
					+ "계정마다 정해진 횟수(맞든 틀리든 센다)를 넘으면 잠시 429(DONGTANPASS_PASS_NO_VERIFY_LIMITED)이고, "
					+ "다시 할 수 있을 때까지 남은 시간은 Retry-After 헤더(초)에 싣는다. QR 확인은 세지 않는다. "
					+ "패스를 받지 않는 지점의 직원은 403(DONGTANPASS_CAMPUS_NOT_PROVIDING, 세지도 토큰을 쓰지도 않는다). "
					+ "쓸 수 없는 패스는 409(DONGTANPASS_PASS_EXPIRED, DONGTANPASS_PASS_CANCELED, DONGTANPASS_PASS_ALREADY_REDEEMED)")
	@NotIdempotent("바꾸는 자료가 없다. 같은 토큰을 다시 보내면 이미 쓴 토큰이라 400이어야 한다(처음의 응답을 다시 주지 않는다)")
	@PostMapping("/verify")
	VerifiedPassResponse verifyPass(@Valid @RequestBody PassVerifyRequest body,
			@AuthenticationPrincipal AuthenticatedUser user) {
		if (body.qrToken() != null) {
			return VerifiedPassResponse.from(deskPassService.verifyQr(body.qrToken(), user));
		}
		return VerifiedPassResponse.from(deskPassService.verifyPassNo(body.passNo(), user));
	}

	@Operation(summary = "본인 미리 확인",
			description = "학생을 등록하기 전에 등록 폼에 적은 학생 이름과 학부모 번호(1~4개)가 패스와 맞는지 따로 답한다. 바꾸는 자료가 없다. "
					+ "이름은 띄어쓰기와 무관하고, 번호는 하나라도 패스를 받을 때 적은 번호와 같으면 맞다. 최종 판단은 사용 처리가 다시 한다. "
					+ "패스를 받지 않는 지점의 직원은 403(DONGTANPASS_CAMPUS_NOT_PROVIDING), 없는 패스는 404(DONGTANPASS_PASS_NOT_FOUND), "
					+ "쓸 수 없는 패스는 409(DONGTANPASS_PASS_EXPIRED, DONGTANPASS_PASS_CANCELED, DONGTANPASS_PASS_ALREADY_REDEEMED). "
					+ "계정마다 정해진 횟수(맞든 틀리든, 404·409도 센다. 패스 번호 확인과는 따로)를 넘으면 잠시 429(DONGTANPASS_HOLDER_CHECK_LIMITED)이고, "
					+ "다시 할 수 있을 때까지 남은 시간은 Retry-After 헤더(초)에 싣는다. 다른 지점 직원의 요청은 세지 않는다. "
					+ "이름과 번호는 주소가 아니라 본문으로만 보낸다(쿼리 문자열이 있으면 400)")
	@NotIdempotent("바꾸는 자료가 없다. 같은 요청을 다시 보내도 결과가 같다")
	@PostMapping("/{id}/holder-check")
	HolderCheckResponse checkHolder(@PathVariable long id, @Valid @RequestBody HolderCheckRequest body,
			@AuthenticationPrincipal AuthenticatedUser user, HttpServletRequest request) {
		// 이름과 번호를 주소에 실어 보낸 요청이다. 읽지 않고 넘기면 주소에 실린 값이 앞단의 접근 로그에 남은 것을 아무도 모른다.
		// 거부해서 드러나게 한다. 이름이나 값은 로그에 남기지 않는다.
		if (!request.getParameterMap().isEmpty()) {
			log.warn("본인 미리 확인에 쿼리 문자열이 실려 왔다. 이름과 번호는 본문으로만 받는다: 파라미터 {}개",
					request.getParameterMap().size());
			throw new BusinessException(CommonErrorCode.BAD_REQUEST, "이름과 번호는 주소가 아니라 본문으로 보내야 합니다.");
		}
		return HolderCheckResponse
			.from(deskPassService.holderCheck(id, body.studentNameKey(), body.guardianPhones(), user));
	}

	@Operation(summary = "패스 사용 처리",
			description = "데스크가 방금 등록한 학생에게 패스를 쓴다(확인 → 본인 미리 확인 → 학생 등록 → 이 요청). 금액과 청구는 다루지 않는다. "
					+ "실패하면 학생을 다시 등록하지 않고 같은 학생 ID로 이 요청만 다시 보낸다. "
					+ "아래 순서로 확인하고 처음 걸린 것으로 답한다. "
					+ "패스를 받지 않는 지점의 직원은 403(DONGTANPASS_CAMPUS_NOT_PROVIDING), 없는 패스는 404(DONGTANPASS_PASS_NOT_FOUND), "
					+ "쓸 수 없는 패스는 409(DONGTANPASS_PASS_EXPIRED, DONGTANPASS_PASS_CANCELED, DONGTANPASS_PASS_ALREADY_REDEEMED), "
					+ "패스 할인이 꺼져 있으면 409(DONGTANPASS_DISCOUNT_INACTIVE), 없는 학생은 404(DONGTANPASS_STUDENT_NOT_FOUND), "
					+ "패스를 받는 지점의 학생이 아니면 409(DONGTANPASS_STUDENT_NOT_AT_PROVIDING_CAMPUS), "
					+ "신규 학생이 아니면(이미 패스를 씀, 학생 자료를 만든 지 7일이 지남, 재원 중이 아님) 409(DONGTANPASS_STUDENT_NOT_NEW), "
					+ "학생 이름이 패스와 다르면 409(DONGTANPASS_HOLDER_NAME_MISMATCH), "
					+ "학생의 학부모 번호 중에 패스를 받을 때 적은 번호가 없으면 409(DONGTANPASS_HOLDER_PHONE_MISMATCH). "
					+ "계정마다 정해진 횟수(맞든 틀리든, 404·409도 센다. 본인 미리 확인과는 따로)를 넘으면 잠시 429(DONGTANPASS_REDEEM_LIMITED)이고, "
					+ "다시 할 수 있을 때까지 남은 시간은 Retry-After 헤더(초)에 싣는다. 다른 지점 직원의 요청은 세지 않는다. "
					+ "같은 패스를 고친 다른 요청과 겹쳐 진 경우 패스가 아직 쓸 수 있으면 409(DONGTANPASS_PASS_CHANGED_CONCURRENTLY)이고 다시 보내면 된다. "
					+ "DONGTANPASS_DISCOUNT_INACTIVE와 DONGTANPASS_PASS_CHANGED_CONCURRENTLY는 같은 Idempotency-Key로 다시 보내도 다시 처리한다")
	@Idempotent
	@PostMapping("/{id}/redeem")
	PassRedeemResponse redeem(@PathVariable long id, @Valid @RequestBody PassRedeemRequest body,
			@AuthenticationPrincipal AuthenticatedUser user) {
		return PassRedeemResponse.from(passRedeemService.redeem(id, body.studentId(), user));
	}

}
