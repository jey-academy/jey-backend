package com.jey.dongtanpass.application;

import java.util.List;

import com.jey.core.audit.api.AuditEntry;
import com.jey.core.audit.api.AuditLog;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.UniqueKeys;
import com.jey.dongtanpass.api.PartnerRefs;
import com.jey.dongtanpass.domain.Partner;
import com.jey.dongtanpass.domain.PartnerRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 연계 학원을 만들고 보여 준다 (ADR-0034). 관리자만 쓴다.
 */
@Service
public class PartnerService {

	// 마이그레이션에 적은 제약 이름. 저장이 어느 제약에 걸렸는지 이것으로 가린다.
	private static final String NAME_UNIQUE_KEY = "uk_dongtanpass_partner_name";

	private final PartnerRepository partners;

	private final TransactionTemplate transaction;

	private final AuditLog auditLog;

	PartnerService(PartnerRepository partners, TransactionTemplate transaction, AuditLog auditLog) {
		this.partners = partners;
		this.transaction = transaction;
		this.auditLog = auditLog;
	}

	/**
	 * 켜진 연계 학원을 만든다.
	 *
	 * @throws BusinessException 같은 이름의 학원이 있을 때
	 * @throws IllegalArgumentException 이름이 비었거나 길 때
	 */
	public PartnerView create(String name) {
		Partner partner = Partner.create(name);
		try {
			return transaction.execute(status -> {
				// 이름이 겹치면 여기서 유니크 제약에 걸린다. 커밋 때까지 미루면 기록을 적은 뒤에야 드러난다.
				Partner saved = partners.saveAndFlush(partner);
				auditLog.record(
						AuditEntry.of(DongtanpassAuditAction.PARTNER_CREATED).target(PartnerRefs.partner(saved.getId())));
				return PartnerView.from(saved);
			});
		}
		catch (DataIntegrityViolationException ex) {
			// 다른 제약에 걸린 것은 그대로 던진다(서버 오류로 드러나야 한다).
			if (UniqueKeys.isViolationOf(ex, NAME_UNIQUE_KEY)) {
				throw new BusinessException(DongtanpassErrorCode.PARTNER_NAME_DUPLICATED, null, ex);
			}
			throw ex;
		}
	}

	/** 만든 순서로 모두 준다. 연계 학원은 몇 곳뿐이라 나누지 않는다. */
	public List<PartnerView> list() {
		return partners.findAllByOrderByIdAsc().stream().map(PartnerView::from).toList();
	}

}
