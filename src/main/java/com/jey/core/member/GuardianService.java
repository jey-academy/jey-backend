package com.jey.core.member;

import com.jey.core.member.domain.Guardian;
import com.jey.core.member.domain.GuardianRepository;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.PhoneNumber;
import com.jey.core.shared.PhoneProtector;
import com.jey.core.shared.UniqueKeys;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 학부모. 전화번호 하나에 학부모는 한 명이다(ADR-0023).
 */
@Service
public class GuardianService {

	private static final String PHONE_UNIQUE_KEY = "uk_core_guardian_phone";

	private final GuardianRepository guardians;

	private final PhoneProtector protector;

	private final TransactionTemplate newTransaction;

	GuardianService(GuardianRepository guardians, PhoneProtector protector,
			PlatformTransactionManager transactionManager) {
		this.guardians = guardians;
		this.protector = protector;
		this.newTransaction = new TransactionTemplate(transactionManager);
		this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * 그 번호의 학부모를 찾고, 없으면 만든다. 부르는 쪽의 트랜잭션과 따로 커밋한다.
	 *
	 * <p>같은 번호로 동시에 부르면 한쪽은 유니크 키에 막힌다. 유니크 키 위반이 난 트랜잭션은 더 쓸 수 없으므로
	 * 새 트랜잭션에서 다시 찾아 먼저 만들어진 학부모를 돌려준다.
	 * 따로 커밋하기 때문에, 부르는 쪽의 뒷일이 실패해도 여기서 만든 학부모는 남는다(학생이 없는 학부모).
	 * 다음에 같은 번호로 등록하면 그 학부모를 다시 쓴다.
	 *
	 * @param name 새로 만들 때만 쓴다. 이미 있으면 저장된 이름을 바꾸지 않는다
	 */
	public EnsuredGuardian findOrCreate(String name, PhoneNumber phone) {
		String index = protector.fullIndex(phone);
		try {
			return newTransaction.execute(status -> guardians.findByPhoneIndex(index)
					.map(found -> new EnsuredGuardian(found.getId(), true))
					.orElseGet(() -> new EnsuredGuardian(
							guardians.saveAndFlush(Guardian.create(name, protector.protect(phone))).getId(), false)));
		}
		catch (DataIntegrityViolationException ex) {
			if (!UniqueKeys.isViolationOf(ex, PHONE_UNIQUE_KEY)) {
				throw ex;
			}
			return newTransaction.execute(status -> guardians.findByPhoneIndex(index)
					.map(found -> new EnsuredGuardian(found.getId(), true))
					.orElseThrow(() -> ex));
		}
	}

	/**
	 * @param phone 새 번호. {@code null}이면 그대로 둔다
	 * @throws BusinessException 없는 학부모이거나, 다른 학부모가 쓰는 번호일 때
	 */
	@Transactional
	public GuardianProfile update(long id, String name, PhoneNumber phone) {
		Guardian found = guardians.findById(id)
				.orElseThrow(() -> new BusinessException(MemberErrorCode.GUARDIAN_NOT_FOUND));
		boolean phoneChanged = false;
		if (phone != null) {
			String index = protector.fullIndex(phone);
			// 값을 바꾸기 전에 확인한다. 바꾼 뒤에 조회하면 조회 직전에 변경이 DB로 나가서 유니크 키 오류가 여기서 먼저 난다.
			if (guardians.existsOtherByPhoneIndex(index, id)) {
				PhoneLookupLog.record(PhoneLookupLog.PHONE_DUPLICATED, null, id);
				throw new BusinessException(MemberErrorCode.GUARDIAN_PHONE_DUPLICATED);
			}
			// 같은 번호를 다시 보낸 것이면 암호문을 새로 만들지 않는다.
			if (!index.equals(found.getPhone().getFullIndex())) {
				found.changePhone(protector.protect(phone));
				phoneChanged = true;
			}
		}
		found.rename(name);
		try {
			guardians.saveAndFlush(found);
		}
		catch (DataIntegrityViolationException ex) {
			if (UniqueKeys.isViolationOf(ex, PHONE_UNIQUE_KEY)) {
				PhoneLookupLog.record(PhoneLookupLog.PHONE_DUPLICATED, null, id);
				throw new BusinessException(MemberErrorCode.GUARDIAN_PHONE_DUPLICATED, null, ex);
			}
			throw ex;
		}
		if (phoneChanged) {
			PhoneLookupLog.record(PhoneLookupLog.PHONE_CHANGED, null, id);
		}
		return new GuardianProfile(found.getId(), found.getName(), protector.reveal(found.getPhone()));
	}

	/**
	 * @param existing 새로 만들지 않고 이미 있던 학부모인지
	 */
	public record EnsuredGuardian(long guardianId, boolean existing) {
	}

	/**
	 * 학생과 무관한 학부모 자신의 정보. 전화번호는 응답이나 로그로 나가면 가린 표시가 된다.
	 */
	public record GuardianProfile(long id, String name, PhoneNumber phone) {
	}

}
