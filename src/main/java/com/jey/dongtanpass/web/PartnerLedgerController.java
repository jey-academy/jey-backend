package com.jey.dongtanpass.web;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import com.jey.core.auth.api.AllowedRoles;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.auth.api.UserRole;
import com.jey.dongtanpass.application.PartnerLedgerExport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 연계 학원이 자기 장부를 CSV로 내려받는다(시트 장부와 같은 칸). 꺼진 학원의 계정은 PartnerActiveInterceptor가 막는다(ADR-0034).
// 관리자는 쓰지 않는다(장부는 그 학원의 것이다. 관리자는 사용 내역 화면과 미반영 목록으로 본다).
@RestController
@RequestMapping("/api/v1/dongtanpass/partner-ledger")
@AllowedRoles(UserRole.PARTNER)
class PartnerLedgerController {

	private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

	private final PartnerLedgerExport export;

	PartnerLedgerController(PartnerLedgerExport export) {
		this.export = export;
	}

	@Operation(summary = "연계 학원 장부 CSV",
			description = "구글 시트 장부와 같은 칸(구분, 사용일, 학생, 패스 번호, 원래 수강료, 할인율, 할인액, 받을 금액, JEY 지점)이다. "
					+ "시트를 쓰든 안 쓰든 받을 수 있다. 두 종류가 섞인다: 이 학원이 할인해 준 기록(구분 \"제이G패스 할인\", JEY 지점은 패스를 발급한 지점), "
					+ "이 학원이 발급한 패스가 JEY 지점에서 쓰인 기록(구분 \"JEY에서 사용\", 금액 칸은 비어 있고 JEY 지점은 쓴 지점). "
					+ "사용 시각 최신 순. 금액은 원 단위 정수 글자(280000), 할인율은 퍼센트 글자(5%). 전화번호는 싣지 않는다. "
					+ "UTF-8에 BOM이 붙어 있다. 수식으로 읽힐 칸(=, +, -, @로 시작)은 앞에 '를 붙인다. "
					+ "기간이 틀렸으면 400(DONGTANPASS_INVALID_PERIOD), 1년을 넘으면 400(DONGTANPASS_PERIOD_TOO_LONG)")
	// 파일 바이트다. 적지 않으면 명세에 base64 글자(format: byte)로 나간다.
	@ApiResponse(responseCode = "200", description = "CSV 파일",
			content = @Content(mediaType = "text/csv", schema = @Schema(type = "string", format = "binary")))
	@GetMapping(value = "/export", produces = "text/csv")
	ResponseEntity<byte[]> export(@AuthenticationPrincipal AuthenticatedUser user,
			@Parameter(description = "이 사용일(한국 날짜)부터, 포함") @RequestParam LocalDate from,
			@Parameter(description = "이 사용일(한국 날짜)까지, 포함. 시작일 + 1년까지") @RequestParam LocalDate to) {
		byte[] file = export.csv(user, from, to);
		// 파일 이름은 영문과 숫자만 쓴다(브라우저마다 한글 파일 이름을 다르게 다룬다).
		ContentDisposition attachment = ContentDisposition.attachment()
			.filename("dongtanpass-partner-ledger.csv")
			.build();
		return ResponseEntity.ok()
			.contentType(CSV)
			.header(HttpHeaders.CONTENT_DISPOSITION, attachment.toString())
			.body(file);
	}

}
