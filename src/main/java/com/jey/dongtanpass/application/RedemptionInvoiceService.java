package com.jey.dongtanpass.application;

import java.time.YearMonth;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.jey.billing.api.InvoiceDiscount;
import com.jey.billing.api.InvoiceIssuing;
import com.jey.billing.api.IssueInvoiceCommand;
import com.jey.billing.api.IssuedInvoice;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.member.api.MemberDirectory;
import com.jey.core.member.api.StudentInfo;
import com.jey.core.shared.BusinessException;
import com.jey.dongtanpass.domain.Pass;
import com.jey.dongtanpass.domain.PassRepository;
import com.jey.dongtanpass.domain.Redemption;
import com.jey.dongtanpass.domain.RedemptionRepository;

/**
 * 데스크가 패스를 쓴 학생의 청구를 그 사용 기록에서 만든다. 패스 할인은 이 경로로만 청구에 붙는다
 * (수납 모듈의 일반 청구 API는 패스 할인을 받지 않는다): 할인 유형과 할인율은 지금의 패스 혜택({@link PassBenefits#current()}),
 * 참조값은 패스 번호다. 직원이 더 고른 할인(형제 등)은 그 뒤에 계산한다.
 *
 * <p>직원은 사용 기록의 지점과 학생의 지금 지점을 모두 다룰 수 있어야 한다. 청구는 학생의 지금 지점에 속하므로, 사용 처리 뒤 학생의
 * 지점이 바뀌었으면 사용 기록의 지점 직원만으로는 만들지 못한다(다른 지점의 청구를 쓰지 않게).
 *
 * <p>사용 기록을 먼저 잠근다(같은 기록으로 동시에 둘을 만들지 않게). 사용 기록 하나에 열린 청구 하나다: 전의 청구가 열려 있으면 409,
 * 취소됐으면 새로 만들고 기록의 청구를 바꾼다. 청구는 이 트랜잭션 안에서 만들어지므로 수납이 거부하면 기록도 그대로다.
 */
@Service
public class RedemptionInvoiceService {

	private final RedemptionRepository redemptions;

	private final PassRepository passes;

	private final PassBenefits passBenefits;

	private final InvoiceIssuing invoiceIssuing;

	private final TransactionTemplate transaction;

	private final MemberDirectory members;

	RedemptionInvoiceService(RedemptionRepository redemptions, PassRepository passes, PassBenefits passBenefits,
			InvoiceIssuing invoiceIssuing, TransactionTemplate transaction, MemberDirectory members) {
		this.redemptions = redemptions;
		this.passes = passes;
		this.passBenefits = passBenefits;
		this.invoiceIssuing = invoiceIssuing;
		this.transaction = transaction;
		this.members = members;
	}

	/**
	 * @param discountCodes 직원이 더 고른 할인 유형 코드(패스 할인은 넣지 않는다)
	 * @throws BusinessException 없는 사용 기록·학생, 다룰 수 없는 지점(사용 기록의 지점이나 학생의 지금 지점), 열린 청구가 이미 있을 때,
	 *     패스 할인이 꺼졌을 때, 수납 모듈이 거부할 때(그 모듈의 코드 — 직원이 패스 할인을 또 고르면 BILLING_DISCOUNT_NEEDS_REFERENCE)
	 */
	public RedemptionInvoice issue(long redemptionId, YearMonth targetMonth, List<IssueInvoiceCommand.Line> lines,
			List<String> discountCodes, AuthenticatedUser user) {
		return transaction.execute(status -> {
			Redemption redemption = redemptions.findByIdForUpdate(redemptionId)
				.orElseThrow(() -> new BusinessException(DongtanpassErrorCode.REDEMPTION_NOT_FOUND));
			user.requireCampus(redemption.getCampusId());
			// 청구가 실제로 속하는 곳(학생의 지금 지점)도 본다.
			StudentInfo student = members.findStudent(redemption.getStudentId())
				.orElseThrow(() -> new BusinessException(DongtanpassErrorCode.STUDENT_NOT_FOUND));
			user.requireCampus(student.campusId());
			if (redemption.getInvoiceId() != null && invoiceIssuing.isOpen(redemption.getInvoiceId())) {
				// 권한 확인 뒤다. 화면이 그 청구로 바로 간다.
				throw new BusinessException(DongtanpassErrorCode.REDEMPTION_ALREADY_INVOICED)
					.withId("invoiceId", redemption.getInvoiceId());
			}
			Pass pass = passes.findById(redemption.getPassId())
				.orElseThrow(() -> new IllegalStateException("사용 기록의 패스가 없다: passId=" + redemption.getPassId()));
			PassBenefit benefit = passBenefits.current();
			// 꺼진 패스 할인은 붙이지 않는다. 수납의 "쓰지 않는 할인 유형"보다 무엇이 꺼졌는지 알려 주는 답이다(데스크 사용 처리와 같은 코드).
			if (!benefit.discountActive()) {
				throw new BusinessException(DongtanpassErrorCode.DISCOUNT_INACTIVE);
			}
			IssuedInvoice issued = invoiceIssuing.issue(new IssueInvoiceCommand(redemption.getStudentId(),
					targetMonth, lines, discountCodes,
					List.of(new InvoiceDiscount(benefit.discountTypeCode(), benefit.discountRate(), pass.getPassNo()))));
			redemption.attachInvoice(issued.invoiceId());
			return new RedemptionInvoice(redemption.getId(), issued.invoiceId(), issued.totalAmount());
		});
	}

}
