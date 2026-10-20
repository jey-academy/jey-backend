package com.jey.dongtanpass.web;

import java.time.LocalDate;

import com.jey.core.auth.api.AllowedRoles;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.CommonErrorCode;
import com.jey.core.shared.Idempotent;
import com.jey.core.shared.NotIdempotent;
import com.jey.core.shared.PageLimits;
import com.jey.dongtanpass.application.PartnerPassService;
import com.jey.dongtanpass.application.PartnerRedemptionLedger;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 연계 학원이 JEY 발급 패스를 확인하고 사용 처리한다(반대 방향, ADR-0040). 꺼진 학원은 인터셉터가 403으로 막는다.
 */
@RestController
@RequestMapping("/api/v1/dongtanpass/partner-redemptions")
class PartnerRedemptionController {

	private static final Logger log = LoggerFactory.getLogger(PartnerRedemptionController.class);

	private final PartnerPassService partnerPassService;

	private final PartnerRedemptionLedger partnerRedemptionLedger;

	PartnerRedemptionController(PartnerPassService partnerPassService,
			PartnerRedemptionLedger partnerRedemptionLedger) {
		this.partnerPassService = partnerPassService;
		this.partnerRedemptionLedger = partnerRedemptionLedger;
	}

	@Operation(summary = "연계 학원 사용 내역",
			description = "연계 학원이 쓴 JEY 발급 패스. 최신 사용 순이다. 연계 학원은 자기 학원 것만 본다(다른 학원을 고르면 403 DONGTANPASS_OTHER_PARTNER_NOT_ALLOWED). "
					+ "관리자는 고르지 않으면 모든 학원이다. 기간은 사용일(한국 날짜)로 자르고 양끝을 포함하며 둘 다 필수다. "
					+ "시작일이 종료일보다 뒤이거나 2000-01-01~2099-12-31 밖이면 400(DONGTANPASS_INVALID_PERIOD), 1년을 넘으면 400(DONGTANPASS_PERIOD_TOO_LONG). "
					+ "동탄관 데스크의 사용 내역(/api/v1/dongtanpass/redemptions)과는 따로다")
	@AllowedRoles({ UserRole.PARTNER, UserRole.ADMIN })
	@GetMapping
	PartnerRedemptionPageResponse list(@AuthenticationPrincipal AuthenticatedUser user,
			@Parameter(description = "연계 학원. 연계 학원 계정은 안 주거나 자기 학원, 관리자는 안 주면 모든 학원") @RequestParam(
					required = false) Long partnerId,
			@Parameter(description = "이 사용일(한국 날짜)부터, 포함") @RequestParam LocalDate from,
			@Parameter(description = "이 사용일(한국 날짜)까지, 포함. 시작일 + 1년까지") @RequestParam LocalDate to,
			@Parameter(description = PageLimits.PAGE_DESCRIPTION) @RequestParam(defaultValue = "0") @Min(0) @Max(
					PageLimits.MAX_PAGE) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(RedemptionController.MAX_PAGE_SIZE) int size) {
		return PartnerRedemptionPageResponse
			.from(partnerRedemptionLedger.page(user, partnerId, from, to, page, size));
	}

	@Operation(summary = "연계 학원 패스 확인",
			description = "학부모 화면의 QR에서 읽은 토큰(qrToken)이나 패스 번호(passNo) 중 하나로 JEY 발급 패스를 확인하고, 다음 단계에 쓸 패스 ID를 준다. "
					+ "둘 다 보내거나 둘 다 없으면 400(COMMON_BAD_REQUEST). QR: 한 토큰은 한 번만 통과한다. 틀렸거나 만료됐거나 이미 쓴 토큰은 400(DONGTANPASS_QR_INVALID). "
					+ "패스 번호: 없는 번호는 404(DONGTANPASS_PASS_NOT_FOUND), 계정마다 정해진 횟수를 넘으면 429(DONGTANPASS_PASS_NO_VERIFY_LIMITED, Retry-After). "
					+ "연계 학원이 발급한 패스는 409(DONGTANPASS_PASS_FOR_CAMPUS_USE), 이 학원에서 이미 JEY 패스 할인을 받은 학생은 409(DONGTANPASS_STUDENT_ALREADY_USED_AT_PARTNER) — "
					+ "이 둘은 토큰을 쓰지 않는다. 쓸 수 없는 패스는 409(DONGTANPASS_PASS_EXPIRED, DONGTANPASS_PASS_CANCELED, DONGTANPASS_PASS_ALREADY_REDEEMED)")
	@AllowedRoles(UserRole.PARTNER)
	@NotIdempotent("바꾸는 자료가 없다. 같은 토큰을 다시 보내면 이미 쓴 토큰이라 400이어야 한다(처음의 응답을 다시 주지 않는다)")
	@PostMapping("/verify")
	PartnerVerifiedPassResponse verify(@Valid @RequestBody PassVerifyRequest body,
			@AuthenticationPrincipal AuthenticatedUser user) {
		if (body.qrToken() != null) {
			return PartnerVerifiedPassResponse.from(partnerPassService.verifyQr(body.qrToken(), user));
		}
		return PartnerVerifiedPassResponse.from(partnerPassService.verifyPassNo(body.passNo(), user));
	}

	@Operation(summary = "연계 학원 본인 확인",
			description = "학원이 등록 서류에 적은 학생 이름과 학부모 번호(1~4개)가 패스와 맞는지 따로 답한다. 바꾸는 자료가 없다. "
					+ "이름은 띄어쓰기와 무관하고, 번호는 하나라도 패스의 번호와 같으면 맞다. 최종 판단은 사용 처리가 다시 한다. "
					+ "없는 패스는 404, 연계 학원 발급 패스·이 학원에서 이미 할인받은 학생·쓸 수 없는 패스는 409(확인과 같은 코드). 이 학원이 확인하지 않은(확인한 지 30분이 지난) 패스도 404. "
					+ "계정마다 정해진 횟수(맞든 틀리든 센다)를 넘으면 429(DONGTANPASS_HOLDER_CHECK_LIMITED, Retry-After). "
					+ "이름과 번호는 주소가 아니라 본문으로만 보낸다(쿼리 문자열이 있으면 400)")
	@AllowedRoles(UserRole.PARTNER)
	@NotIdempotent("바꾸는 자료가 없다. 같은 요청을 다시 보내도 결과가 같다")
	@PostMapping("/{passId}/holder-check")
	HolderCheckResponse checkHolder(@PathVariable long passId, @Valid @RequestBody HolderCheckRequest body,
			@AuthenticationPrincipal AuthenticatedUser user, HttpServletRequest request) {
		rejectQueryString(request);
		return HolderCheckResponse
			.from(partnerPassService.holderCheck(passId, body.studentNameKey(), body.guardianPhones(), user));
	}

	@Operation(summary = "받을 금액 미리 보기",
			description = "원래 수강료(첫 달 한 달 치)를 넣으면 이 패스의 할인율로 할인액과 받을 금액을 준다. 기록을 남기지 않는다. "
					+ "받을 금액은 1원 미만을 버리고, 할인액은 원래 수강료 − 받을 금액이다. 규칙 밖의 수강료는 그 칸의 400(COMMON_VALIDATION_FAILED). "
					+ "없는 패스는 404, 연계 학원 발급 패스·이 학원에서 이미 할인받은 학생·쓸 수 없는 패스는 409. 이 학원이 확인하지 않은(확인한 지 30분이 지난) 패스도 404")
	@AllowedRoles(UserRole.PARTNER)
	@NotIdempotent("바꾸는 자료가 없다. 같은 요청을 다시 보내도 결과가 같다")
	@PostMapping("/{passId}/quote")
	PartnerQuoteResponse quote(@PathVariable long passId, @Valid @RequestBody PartnerQuoteRequest body,
			@AuthenticationPrincipal AuthenticatedUser user) {
		return PartnerQuoteResponse.from(partnerPassService.quote(passId, body.originalFee(), user));
	}

	@Operation(summary = "연계 학원 사용 처리",
			description = "이 학원이 방금 확인한 JEY 발급 패스를 쓴다. 이름·학부모 번호를 서버가 다시 맞춰 보고, 받을 금액을 계산해 기록한다. "
					+ "Idempotency-Key 필요(같은 키로 다시 보내면 처음의 응답). 원래 수강료가 규칙 밖이면 400(DONGTANPASS_INVALID_ORIGINAL_FEE). "
					+ "이 학원이 확인하지 않은(확인한 지 30분이 지난) 패스는 404(DONGTANPASS_PASS_NOT_FOUND). "
					+ "연계 학원 발급 패스 409(DONGTANPASS_PASS_FOR_CAMPUS_USE), 쓸 수 없는 패스 409(DONGTANPASS_PASS_EXPIRED, DONGTANPASS_PASS_CANCELED, DONGTANPASS_PASS_ALREADY_REDEEMED), "
					+ "이름이 다름 409(DONGTANPASS_HOLDER_NAME_MISMATCH), 번호가 다름 409(DONGTANPASS_HOLDER_PHONE_MISMATCH), "
					+ "이 학원에서 이미 할인받은 학생 409(DONGTANPASS_STUDENT_ALREADY_USED_AT_PARTNER). "
					+ "계정마다 정해진 횟수(맞든 틀리든 센다)를 넘으면 429(DONGTANPASS_REDEEM_LIMITED, Retry-After). "
					+ "이름과 번호는 주소가 아니라 본문으로만 보낸다(쿼리 문자열이 있으면 400)")
	@AllowedRoles(UserRole.PARTNER)
	@Idempotent
	@PostMapping("/{passId}/redeem")
	PartnerRedeemResponse redeem(@PathVariable long passId, @Valid @RequestBody PartnerRedeemRequest body,
			@AuthenticationPrincipal AuthenticatedUser user, HttpServletRequest request) {
		rejectQueryString(request);
		return PartnerRedeemResponse.from(partnerPassService.redeem(passId, body.studentNameKey(),
				body.guardianPhones(), body.originalFee(), user));
	}

	// 이름과 번호를 주소에 실어 보낸 요청이다. 읽지 않고 넘기면 주소에 실린 값이 앞단의 접근 로그에 남은 것을 아무도 모른다.
	// 거부해서 드러나게 한다(ADR-0032). 이름이나 값은 로그에 남기지 않는다.
	private static void rejectQueryString(HttpServletRequest request) {
		if (!request.getParameterMap().isEmpty()) {
			log.warn("연계 학원 요청에 쿼리 문자열이 실려 왔다. 이름과 번호는 본문으로만 받는다: 파라미터 {}개",
					request.getParameterMap().size());
			throw new BusinessException(CommonErrorCode.BAD_REQUEST, "이름과 번호는 주소가 아니라 본문으로 보내야 합니다.");
		}
	}

}
