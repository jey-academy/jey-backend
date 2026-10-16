package com.jey.dongtanpass.web;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import com.jey.core.auth.api.AllowedRoles;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import com.jey.core.shared.Idempotent;
import com.jey.core.shared.PageLimits;
import com.jey.dongtanpass.application.ConsentNotice;
import com.jey.dongtanpass.application.PassLedger;
import com.jey.dongtanpass.application.PassService;
import com.jey.dongtanpass.domain.PassState;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 연계 학원이 패스를 발급하고 장부를 본다. 꺼진 학원의 계정은 PartnerActiveInterceptor가 막는다(ADR-0034).
// 관리자도 쓰지 않는다. 연계 학원 식별자가 없고, 발급은 결제를 확인한 그 학원의 일이다.
@RestController
@RequestMapping("/api/v1/dongtanpass")
@AllowedRoles(UserRole.PARTNER)
class PassController {

	static final int MAX_PAGE_SIZE = 100;

	private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

	private final PassService passService;

	private final PassLedger passLedger;

	PassController(PassService passService, PassLedger passLedger) {
		this.passService = passService;
		this.passLedger = passLedger;
	}

	@Operation(summary = "고지 문구", description = "발급 화면에 보여 줄 지금 문구와 버전. 발급 요청에 이 버전을 함께 보낸다")
	@GetMapping("/consent-notice")
	ConsentNoticeResponse consentNotice() {
		return ConsentNoticeResponse.from(ConsentNotice.CURRENT);
	}

	@Operation(summary = "패스 발급", description = "결제를 확인한 뒤 보낸다. 링크 토큰은 이 응답에만 있다. 잃어버리면 링크를 새로 만든다")
	@Idempotent
	@PostMapping("/passes")
	@ResponseStatus(HttpStatus.CREATED)
	PassIssueResponse issue(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody PassIssueRequest body) {
		return PassIssueResponse.from(passService.issue(partnerIdOf(user), body.toNewPass()));
	}

	@Operation(summary = "링크 새로 만들기",
			description = "학부모 링크의 토큰을 새로 만든다. 예전 링크는 바로 쓸 수 없게 된다. 새 토큰은 이 응답에만 있다. 사용됐거나 취소된 패스는 409")
	@Idempotent
	@PostMapping("/passes/{id}/link")
	PassLinkResponse renewLink(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
		return PassLinkResponse.from(passService.renewLink(partnerIdOf(user), id));
	}

	@Operation(summary = "패스 취소", description = "쓰지 않은 패스를 취소한다. 기간이 지난 패스도 된다. 결제 환불은 따로 한다. 사용됐거나 이미 취소된 패스는 409")
	@Idempotent
	@PostMapping("/passes/{id}/cancel")
	PassCancelResponse cancel(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
		return PassCancelResponse.from(passService.cancel(partnerIdOf(user), id));
	}

	@Operation(summary = "발급 장부", description = "이 학원이 발급한 패스. 최신 발급 순이다. 학부모 번호는 없다")
	@GetMapping("/passes")
	LedgerPageResponse ledger(@AuthenticationPrincipal AuthenticatedUser user,
			@Parameter(description = "이 발급일(한국 날짜)부터, 포함. 2000-01-01~2099-12-31") @RequestParam(
					required = false) LocalDate from,
			@Parameter(description = "이 발급일(한국 날짜)까지, 포함. from보다 앞이거나 2000-01-01~2099-12-31 밖이면 400") @RequestParam(
					required = false) LocalDate to,
			@Parameter(description = "오늘 기준으로 보이는 상태. 안 주면 모두") @RequestParam(required = false) PassState status,
			@Parameter(description = PageLimits.PAGE_DESCRIPTION) @RequestParam(defaultValue = "0") @Min(0) @Max(
					PageLimits.MAX_PAGE) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
		return LedgerPageResponse.from(passLedger.page(partnerIdOf(user), from, to, status, page, size));
	}

	@Operation(summary = "발급 장부 CSV",
			description = "장부와 같은 학원·기간 조건이다(상태로 거르지 않는다). UTF-8에 BOM이 붙어 있다. 수식으로 읽힐 칸(=, +, -, @로 시작)은 앞에 '를 붙인다")
	// 파일 바이트다. 적지 않으면 명세에 base64 글자(format: byte)로 나간다.
	@ApiResponse(responseCode = "200", description = "CSV 파일",
			content = @Content(mediaType = "text/csv", schema = @Schema(type = "string", format = "binary")))
	@GetMapping(value = "/passes/export", produces = "text/csv")
	ResponseEntity<byte[]> export(@AuthenticationPrincipal AuthenticatedUser user,
			@Parameter(description = "이 발급일(한국 날짜)부터, 포함. 2000-01-01~2099-12-31") @RequestParam(
					required = false) LocalDate from,
			@Parameter(description = "이 발급일(한국 날짜)까지, 포함. from보다 앞이거나 2000-01-01~2099-12-31 밖이면 400") @RequestParam(
					required = false) LocalDate to) {
		byte[] file = passLedger.csv(partnerIdOf(user), from, to);
		// 파일 이름은 영문과 숫자만 쓴다(브라우저마다 한글 파일 이름을 다르게 다룬다).
		ContentDisposition attachment = ContentDisposition.attachment().filename("dongtanpass-passes.csv").build();
		return ResponseEntity.ok()
			.contentType(CSV)
			.header(HttpHeaders.CONTENT_DISPOSITION, attachment.toString())
			.body(file);
	}

	// 연계 학원 계정에는 학원 식별자가 있다(세션 확인이 그 조합만 통과시킨다). 없으면 설정이 어긋난 것이다.
	private static long partnerIdOf(AuthenticatedUser user) {
		if (user.partnerId() == null) {
			throw new IllegalStateException("연계 학원 식별자가 없는 연계 학원 계정이다: accountId=" + user.id());
		}
		return user.partnerId();
	}

}
