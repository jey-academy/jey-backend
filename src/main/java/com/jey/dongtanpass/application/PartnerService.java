package com.jey.dongtanpass.application;

import java.util.List;

import com.jey.core.audit.api.AuditEntry;
import com.jey.core.audit.api.AuditLog;
import com.jey.core.auth.api.NewPartnerAccount;
import com.jey.core.auth.api.PartnerAccount;
import com.jey.core.auth.api.PartnerAccounts;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.UniqueKeys;
import com.jey.dongtanpass.api.PartnerRefs;
import com.jey.dongtanpass.domain.Partner;
import com.jey.dongtanpass.domain.PartnerRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 연계 학원과 그 계정을 만들고 보여 준다 (ADR-0034). 관리자만 쓴다.
 *
 * <p>계정은 기초 모듈이 만든다({@link PartnerAccounts}). 그 학원이 있고 켜져 있는지는 여기서 확인한다(기초 모듈은 이 모듈을 모른다).
 */
@Service
public class PartnerService {

	// 마이그레이션에 적은 제약 이름. 저장이 어느 제약에 걸렸는지 이것으로 가린다.
	private static final String NAME_UNIQUE_KEY = "uk_dongtanpass_partner_name";

	private final PartnerRepository partners;

	private final TransactionTemplate transaction;

	private final AuditLog auditLog;

	private final PartnerAccounts partnerAccounts;

	PartnerService(PartnerRepository partners, TransactionTemplate transaction, AuditLog auditLog,
			PartnerAccounts partnerAccounts) {
		this.partners = partners;
		this.transaction = transaction;
		this.auditLog = auditLog;
		this.partnerAccounts = partnerAccounts;
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

	/**
	 * 그 학원의 공용 계정을 만든다. 계정을 만드는 일은 트랜잭션 밖에서 해야 하므로(기초 모듈의 규칙) 학원을 잠그지 않고 읽는다.
	 * 확인한 직후에 학원이 꺼지면 계정이 차단되지 않은 채 남을 수 있다. 그 계정은 패스 기능을 쓰지 못하고(끈 학원은 요청마다 막힌다),
	 * 끄기를 다시 부르면 차단된다.
	 *
	 * @throws BusinessException 학원이 없거나 꺼져 있을 때, 아이디가 이미 있거나 비밀번호가 규칙에 맞지 않을 때
	 */
	public PartnerAccount createAccount(long partnerId, String loginId, String name, String password) {
		Partner partner = partners.findById(partnerId)
			.orElseThrow(() -> new BusinessException(DongtanpassErrorCode.PARTNER_NOT_FOUND));
		if (!partner.isActive()) {
			throw new BusinessException(DongtanpassErrorCode.PARTNER_INACTIVE_FOR_ACCOUNT);
		}
		return partnerAccounts.create(new NewPartnerAccount(partnerId, loginId, name, password));
	}

	/**
	 * 학원을 끄고 그 학원의 활성 계정을 모두 차단한다(세션도 끊긴다). 이미 발급한 패스는 그대로 쓸 수 있다.
	 *
	 * <p>학원 상태를 먼저 커밋하고, 그 뒤 트랜잭션 밖에서 계정을 하나씩 차단한다(계정 차단은 기초 모듈이 따로 커밋한다).
	 * 그래서 둘은 한 번에 끝나지 않는다. 막는 기준은 학원 상태다: 끈 학원의 계정은 패스 모듈의 API를 요청마다 막힌다
	 * ({@code PartnerActiveInterceptor}). 계정 차단이 중간에 실패하면 예외를 그대로 던진다. 학원은 꺼진 채로 남고,
	 * 다시 부르면 아직 차단되지 않은 계정만 차단한다(이미 꺼진 학원은 기록을 다시 남기지 않는다).
	 *
	 * @throws BusinessException 학원이 없을 때
	 * @throws IllegalStateException 다른 트랜잭션 안에서 불렀을 때
	 */
	public PartnerView deactivate(long partnerId) {
		requireNoTransaction("deactivate");
		PartnerView deactivated = transaction.execute(status -> {
			Partner partner = lockedPartner(partnerId);
			if (partner.deactivate()) {
				auditLog.record(
						AuditEntry.of(DongtanpassAuditAction.PARTNER_DEACTIVATED).target(PartnerRefs.partner(partnerId)));
			}
			return PartnerView.from(partner);
		});
		partnerAccounts.disableAll(partnerId);
		return deactivated;
	}

	/**
	 * 학원을 켠다. 끌 때 차단한 계정은 풀지 않는다(끄기 전에 따로 차단해 둔 계정까지 풀리지 않게). 관리자가 계정 관리에서 푼다.
	 *
	 * @throws BusinessException 학원이 없을 때
	 */
	public PartnerView activate(long partnerId) {
		return transaction.execute(status -> {
			Partner partner = lockedPartner(partnerId);
			if (partner.activate()) {
				auditLog.record(
						AuditEntry.of(DongtanpassAuditAction.PARTNER_ACTIVATED).target(PartnerRefs.partner(partnerId)));
			}
			return PartnerView.from(partner);
		});
	}

	/**
	 * 그 학원이 켜져 있는지. 연계 학원 계정의 요청마다 부른다. 없는 학원은 꺼진 것으로 본다.
	 * 읽다 예외가 나면 그대로 던진다(확인하지 못한 요청을 통과시키지 않는다).
	 */
	public boolean isActive(long partnerId) {
		return partners.findById(partnerId).map(Partner::isActive).orElse(false);
	}

	/** 만든 순서로 모두 준다. 연계 학원은 몇 곳뿐이라 나누지 않는다. */
	public List<PartnerView> list() {
		return partners.findAllByOrderByIdAsc().stream().map(PartnerView::from).toList();
	}

	private Partner lockedPartner(long partnerId) {
		return partners.findByIdForUpdate(partnerId)
			.orElseThrow(() -> new BusinessException(DongtanpassErrorCode.PARTNER_NOT_FOUND));
	}

	// 바깥 트랜잭션에 합류하면 학원을 끈 것이 커밋되기 전에 계정을 차단하게 된다(계정 차단도 트랜잭션 밖에서만 부를 수 있다).
	private static void requireNoTransaction(String method) {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("PartnerService#" + method + "은(는) 트랜잭션 밖에서 호출해야 한다");
		}
	}

}
