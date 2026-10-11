package com.jey.core.audit;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.jey.core.audit.api.AuditLog;
import com.jey.core.audit.api.AuditTarget;
import com.jey.core.audit.domain.AuditRecord;
import com.jey.core.audit.domain.AuditRecordRepository;
import com.jey.core.auth.api.AuthenticatedUser;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.DomainEvent;
import com.jey.core.shared.EventMetadata;
import com.jey.core.shared.UniqueKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 감사 로그를 적고 읽는다(ADR-0024). 기록은 두 길로 들어온다.
 * {@link AuditLog}를 직접 부르는 길과, 도메인 이벤트를 {@link AuditEventListener}가 받아 넘기는 길이다.
 */
@Service
public class AuditLogService implements AuditLog {

	private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

	private static final String EVENT_UNIQUE_KEY = "uk_core_audit_log_event";

	// 이벤트 이름과 같은 형식이다. 이벤트로 들어온 기록은 이벤트 이름이 그대로 행위 이름이 된다.
	private static final Pattern ACTION = Pattern.compile("[a-z][a-z0-9]*\\.[a-z][a-z0-9]*(-[a-z0-9]+)*");

	private static final Pattern PHONE_SEPARATORS = Pattern.compile("[\\s.-]");

	// 구분 기호를 뗀 뒤 휴대전화 번호가 들어 있는지 본다. 국가 번호(+82)가 붙은 것도 본다.
	private static final Pattern PHONE_LIKE = Pattern.compile("(?<!\\d)(?:82)?0?1[016789]\\d{7,8}(?!\\d)");

	private static final int MAX_STRING_VALUE_LENGTH = 200;

	private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"));

	// 기간을 주지 않았을 때의 끝. 날짜 타입 파라미터에 null을 넘기지 않기 위해 값으로 채운다.
	private static final Instant FAR_FUTURE = Instant.parse("9999-01-01T00:00:00Z");

	private final AuditRecordRepository records;

	private final JsonMapper jsonMapper;

	private final TransactionTemplate newTransaction;

	private final Clock clock;

	AuditLogService(AuditRecordRepository records, JsonMapper jsonMapper,
			PlatformTransactionManager transactionManager, Clock clock) {
		this.records = records;
		this.jsonMapper = jsonMapper;
		this.newTransaction = new TransactionTemplate(transactionManager);
		this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.clock = clock;
	}

	@Override
	@Transactional
	public void record(String action, AuditTarget target, Map<String, ?> detail) {
		records.save(newDirectRecord(action, target, detail));
	}

	@Override
	public void recordAttempt(String action, AuditTarget target, Map<String, ?> detail) {
		AuditRecord record = newDirectRecord(action, target, detail);
		newTransaction.executeWithoutResult(status -> records.save(record));
	}

	/**
	 * 도메인 이벤트를 한 줄로 적는다. 같은 이벤트가 다시 와도 한 줄만 남는다.
	 *
	 * <p>리스너는 실패하면 다시 실행된다. 저장한 뒤 완료 표시 전에 죽으면 같은 이벤트가 또 온다.
	 * 그때 유니크 키 위반을 실패로 돌려주면 끝없이 다시 온다. 이미 적힌 것이므로 성공으로 끝낸다.
	 * 유니크 키 위반이 난 트랜잭션은 더 쓸 수 없어서 저장은 따로 커밋한다.
	 */
	void recordEvent(DomainEvent event) {
		EventMetadata metadata = event.metadata();
		String eventId = metadata.eventId().toString();
		if (records.existsByEventId(eventId)) {
			return;
		}
		AuditRecord record = AuditRecord.ofEvent(metadata.eventId(), toStored(metadata.occurredAt()),
				metadata.actorId(), metadata.eventType(), metadata.campusId(), eventDetail(event));
		try {
			newTransaction.executeWithoutResult(status -> records.saveAndFlush(record));
		}
		catch (DataIntegrityViolationException ex) {
			// 확인과 저장 사이에 같은 이벤트가 먼저 적힌 경우다. 다른 저장 실패는 그대로 던져 다시 시도되게 한다.
			if (!UniqueKeys.isViolationOf(ex, EVENT_UNIQUE_KEY)) {
				throw ex;
			}
		}
	}

	/**
	 * 최신순으로 읽는다.
	 *
	 * @param from 이 시각부터(포함). {@code null}이면 처음부터
	 * @param to 이 시각 전까지(제외). {@code null}이면 끝까지
	 * @param page 0부터
	 * @throws BusinessException {@code from}이 {@code to}보다 늦을 때
	 */
	@Transactional(readOnly = true)
	public Page<AuditRecord> search(Long actorId, String action, String targetType, Long targetId, Instant from,
			Instant to, int page, int size) {
		Instant start = (from != null) ? from : Instant.EPOCH;
		Instant end = (to != null) ? to : FAR_FUTURE;
		if (start.isAfter(end)) {
			throw new BusinessException(AuditErrorCode.INVALID_PERIOD);
		}
		return records.search(actorId, blankToNull(action), blankToNull(targetType), targetId, start, end,
				PageRequest.of(page, size, NEWEST_FIRST));
	}

	private AuditRecord newDirectRecord(String action, AuditTarget target, Map<String, ?> detail) {
		if (action == null || action.length() > AuditRecord.MAX_ACTION_LENGTH || !ACTION.matcher(action).matches()) {
			throw new IllegalArgumentException("행위 이름은 {모듈}.{사건} 형식의 소문자 kebab-case여야 한다: " + action);
		}
		return AuditRecord.direct(toStored(clock.instant()), AuthenticatedUser.currentId().orElse(null), action,
				(target != null) ? target.type() : null, (target != null) ? target.id() : null, directDetail(detail));
	}

	// 직접 적는 쪽의 상세는 부르는 코드가 만든다. 규칙에 안 맞으면 그 코드의 잘못이므로 기록하지 않고 멈춘다.
	private String directDetail(Map<String, ?> detail) {
		if (detail == null || detail.isEmpty()) {
			return null;
		}
		// 키 순서를 고정해서 같은 내용이 같은 문자열이 되게 한다.
		Map<String, Object> sorted = new TreeMap<>();
		detail.forEach((key, value) -> sorted.put(key, allowedValue(key, value)));
		String json = jsonMapper.writeValueAsString(sorted);
		if (json.length() > AuditRecord.MAX_DETAIL_LENGTH) {
			throw new IllegalArgumentException("감사 로그의 상세가 너무 크다(" + AuditRecord.MAX_DETAIL_LENGTH + "자 초과)");
		}
		return json;
	}

	// 예외 문구에 값을 싣지 않는다. 걸린 값이 전화번호일 수 있다.
	private static Object allowedValue(String key, Object value) {
		if (value instanceof Number || value instanceof Boolean) {
			return value;
		}
		if (value instanceof Enum<?> constant) {
			return constant.name();
		}
		if (value instanceof String text) {
			if (text.length() > MAX_STRING_VALUE_LENGTH) {
				throw new IllegalArgumentException("감사 로그 상세의 문자열이 너무 길다: " + key);
			}
			if (looksLikePhone(text)) {
				throw new IllegalArgumentException("감사 로그 상세에 전화번호처럼 보이는 값이 있다: " + key);
			}
			return text;
		}
		throw new IllegalArgumentException("감사 로그 상세에는 숫자, 참·거짓, enum, 문자열만 넣을 수 있다: " + key);
	}

	// 이벤트의 내용(봉투 제외)을 그대로 싣는다. 이벤트에는 개인정보를 넣지 않는 것이 규칙이지만, 어긴 이벤트가 와도 그대로 적지 않는다.
	// 여기서 예외를 던지면 그 이벤트가 끝없이 다시 온다. 내용 없이 적고 표시를 남긴다.
	private String eventDetail(DomainEvent event) {
		JsonNode tree = jsonMapper.valueToTree(event);
		if (!(tree instanceof ObjectNode body)) {
			return null;
		}
		body.remove("metadata");
		if (body.isEmpty()) {
			return null;
		}
		String json = jsonMapper.writeValueAsString(body);
		if (hasPhoneLikeText(body)) {
			log.error("전화번호처럼 보이는 값이 든 이벤트라 내용 없이 기록한다: eventType={} eventId={}",
					event.metadata().eventType(), event.metadata().eventId());
			return "{\"redacted\":true}";
		}
		if (json.length() > AuditRecord.MAX_DETAIL_LENGTH) {
			return "{\"truncated\":true}";
		}
		return json;
	}

	// 문자열 값만 본다. 숫자로 실린 ID가 우연히 전화번호 모양이어도 걸리지 않는다.
	private static boolean hasPhoneLikeText(JsonNode node) {
		if (node.isString()) {
			return looksLikePhone(node.asString());
		}
		for (JsonNode child : node) {
			if (hasPhoneLikeText(child)) {
				return true;
			}
		}
		return false;
	}

	private static boolean looksLikePhone(String text) {
		return PHONE_LIKE.matcher(PHONE_SEPARATORS.matcher(text).replaceAll("")).find();
	}

	// DB(DATETIME(6))는 마이크로초까지만 저장한다. 저장한 값과 다시 읽은 값이 같도록 미리 맞춘다.
	private static Instant toStored(Instant instant) {
		return instant.truncatedTo(ChronoUnit.MICROS);
	}

	private static String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value.strip();
	}

}
