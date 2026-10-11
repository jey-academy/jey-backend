package com.jey.core.audit;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.jey.core.audit.api.AuditAction;
import com.jey.core.audit.api.AuditEntry;
import com.jey.core.audit.api.AuditLog;
import com.jey.core.audit.api.AuditOutcome;
import com.jey.core.audit.api.DetailMarker;
import com.jey.core.audit.domain.AuditRecord;
import com.jey.core.audit.domain.AuditRecordRepository;
import com.jey.core.auth.api.CurrentActor;
import com.jey.core.shared.BusinessException;
import com.jey.core.shared.DomainEvent;
import com.jey.core.shared.EntityRef;
import com.jey.core.shared.EventMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
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

	private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id"));

	// 기간을 주지 않았을 때의 끝. 날짜 타입 파라미터에 null을 넘기지 않기 위해 값으로 채운다.
	private static final Instant FAR_FUTURE = Instant.parse("9999-01-01T00:00:00Z");

	private static final TypeReference<Map<String, Object>> DETAIL = new TypeReference<>() {
	};

	private final AuditRecordRepository records;

	private final JsonMapper jsonMapper;

	private final TransactionTemplate transaction;

	private final CurrentActor currentActor;

	private final Clock clock;

	AuditLogService(AuditRecordRepository records, JsonMapper jsonMapper,
			PlatformTransactionManager transactionManager, CurrentActor currentActor, Clock clock) {
		this.records = records;
		this.jsonMapper = jsonMapper;
		this.transaction = new TransactionTemplate(transactionManager);
		this.currentActor = currentActor;
		this.clock = clock;
	}

	@Override
	public void record(AuditEntry entry) {
		AuditAction action = entry.action();
		Long actorId = currentActor.get().idOrNull();
		AuditRecord record = AuditRecord.direct(toStored(clock.instant()), actorId, validCode(action),
				action.outcome(), entry.target(), entry.campusId(), directDetail(entry));
		boolean inTransaction = TransactionSynchronizationManager.isActualTransactionActive();
		if (action.outcome() == AuditOutcome.DONE) {
			// 한 일은 그 일과 같은 트랜잭션에 적는다. 트랜잭션 밖에서 적으면 그 일이 롤백돼도 기록만 남는다.
			if (!inTransaction) {
				throw new IllegalStateException("한 일의 기록은 그 일의 트랜잭션 안에서 남겨야 한다: " + action.code());
			}
			records.save(record);
			return;
		}
		// 거부된 시도는 그 요청의 트랜잭션이 끝난 뒤에 적는다. 안에서 적으면 요청이 DB 연결을 쥔 채 하나를 더 기다린다.
		if (inTransaction) {
			throw new IllegalStateException("거부된 시도의 기록은 트랜잭션이 끝난 뒤에 남겨야 한다: " + action.code());
		}
		// 거부 기록은 "누가 받았는지"를 남기려는 것이다. 요청을 다른 스레드로 넘긴 코드에서 부르면 로그인 정보가 따라오지 않는다.
		if (actorId == null) {
			log.warn("행위자 없이 거부된 시도를 기록한다. 요청 스레드 밖에서 불렀는지 확인한다: action={}", action.code());
		}
		transaction.executeWithoutResult(status -> records.save(record));
	}

	/**
	 * 도메인 이벤트를 한 줄로 적는다. 같은 이벤트가 다시 와도 한 줄만 남는다.
	 *
	 * <p>리스너가 실패하면 그 이벤트는 다시 보내진다(ADR-0025). 저장한 뒤 완료 표시 전에 죽으면 같은 이벤트가 또 온다.
	 * 이미 적힌 이벤트는 아무것도 하지 않고 성공으로 끝낸다. 리스너의 트랜잭션에 같이 적으므로 DB 연결을 더 쓰지 않는다.
	 *
	 * <p>이벤트의 내용이나 대상이 규칙에 안 맞아도(너무 크다, 전화번호처럼 보이는 값이 있다, 읽을 수 없다, 대상을 만들다 실패한다)
	 * 예외를 던지지 않는다. 던지면 그 이벤트는 몇 번을 다시 보내도 같은 자리에서 실패한다. 그 부분 없이 적고 표시와 로그를 남긴다.
	 * 던지는 것은 봉투가 없을 때와 저장에 실패했을 때뿐이다. 저장 실패는 다시 보내면 될 수 있다.
	 *
	 * @throws NullPointerException 이벤트에 봉투가 없을 때. 누가 언제 무엇을 했는지 알 수 없어 적을 수 없다
	 */
	void recordEvent(DomainEvent event) {
		EventMetadata metadata = Objects.requireNonNull(event.metadata(),
				() -> event.getClass().getName() + "의 metadata가 없다");
		EntityRef subject = subjectOf(event, metadata);
		String detail = eventDetail(event, metadata);
		// 리스너의 트랜잭션이 있으면 거기에 합류하고, 없으면 여기서 연다.
		transaction.executeWithoutResult(status -> records.insertEventIfAbsent(metadata.eventId().toString(),
				toStored(metadata.occurredAt()), metadata.actorId(), metadata.eventType(),
				AuditOutcome.DONE.name(), (subject != null) ? subject.type() : null,
				(subject != null) ? subject.id() : null, metadata.campusId(), detail, toStored(clock.instant())));
	}

	/**
	 * 최신순으로 읽는다.
	 *
	 * @param outcome {@code null}이면 한 일과 거부된 시도를 모두
	 * @param from 이 시각부터(포함). {@code null}이면 처음부터
	 * @param to 이 시각 전까지(제외). {@code null}이면 끝까지
	 * @param page 0부터
	 * @throws BusinessException {@code from}이 {@code to}보다 늦거나, 대상의 종류 없이 ID만 주었을 때
	 */
	@Transactional(readOnly = true)
	public Page<AuditRecord> search(Long actorId, String action, AuditOutcome outcome, String targetType,
			Long targetId, Instant from, Instant to, int page, int size) {
		Instant start = (from != null) ? from : Instant.EPOCH;
		Instant end = (to != null) ? to : FAR_FUTURE;
		if (start.isAfter(end)) {
			throw new BusinessException(AuditErrorCode.INVALID_PERIOD);
		}
		String type = blankToNull(targetType);
		if (targetId != null && type == null) {
			throw new BusinessException(AuditErrorCode.TARGET_TYPE_REQUIRED);
		}
		Set<AuditOutcome> outcomes = (outcome != null) ? EnumSet.of(outcome) : EnumSet.allOf(AuditOutcome.class);
		return records.search(actorId, blankToNull(action), outcomes, type, targetId, start, end,
				PageRequest.of(page, size, NEWEST_FIRST));
	}

	/**
	 * 저장된 상세를 읽는다. 값이 깨져 있으면(DB를 직접 고친 경우 등) 그 줄만 {@link DetailMarker#CORRUPTED}로 돌려준다.
	 * 한 줄 때문에 목록 전체가 실패하면, 기록이 고쳐졌는지 살펴야 할 때 조회가 막힌다.
	 *
	 * @return 상세가 없으면 {@code null}
	 */
	public Map<String, Object> detailOf(AuditRecord record) {
		if (record.getDetailJson() == null) {
			return null;
		}
		try {
			Map<String, Object> detail = jsonMapper.readValue(record.getDetailJson(), DETAIL);
			if (detail != null) {
				return detail;
			}
			// 저장된 값이 JSON의 null이다. 코드가 그렇게 적는 일은 없다.
			log.error("감사 로그의 상세가 깨져 있다: id={} 원인=null 값", record.getId());
		}
		catch (JacksonException ex) {
			// 예외 문구에는 깨진 내용의 일부가 들어 있을 수 있어 종류만 남긴다. 조회할 때마다 찍히므로 스택도 남기지 않는다.
			log.error("감사 로그의 상세가 깨져 있다: id={} 원인={}", record.getId(), ex.getClass().getSimpleName());
		}
		return DetailMarker.CORRUPTED.asMap();
	}

	private static String validCode(AuditAction action) {
		String code = action.code();
		if (!EventMetadata.isValidName(code)) {
			throw new IllegalArgumentException("행위 이름은 {모듈}.{사건} 형식의 소문자 kebab-case여야 한다: " + code);
		}
		if (action.outcome() == null) {
			throw new IllegalArgumentException("행위의 결과(한 일인지 거부된 시도인지)가 정해져 있지 않다: " + code);
		}
		return code;
	}

	// 직접 적는 쪽의 내용은 부르는 코드가 만든다. 규칙에 안 맞으면 그 코드의 잘못이므로 기록하지 않고 멈춘다.
	// 예외 문구에 값을 싣지 않는다. 걸린 값이 전화번호일 수 있다.
	private String directDetail(AuditEntry entry) {
		Map<String, Object> detail = entry.detail();
		if (detail.isEmpty()) {
			return null;
		}
		detail.forEach((key, value) -> {
			if (value instanceof String text && PhoneLikeText.found(text)) {
				throw new IllegalArgumentException("감사 로그에 전화번호처럼 보이는 값을 실을 수 없다: " + key);
			}
		});
		String json = jsonMapper.writeValueAsString(detail);
		if (json.length() > AuditRecord.MAX_DETAIL_LENGTH) {
			throw new IllegalArgumentException("감사 로그의 상세가 너무 크다(" + AuditRecord.MAX_DETAIL_LENGTH + "자 초과)");
		}
		return json;
	}

	// 이벤트가 대상을 만들다 실패해도(예: 0 이하의 ID) 그 이벤트의 기록 자체는 남긴다.
	private static EntityRef subjectOf(DomainEvent event, EventMetadata metadata) {
		try {
			return event.subject().orElse(null);
		}
		catch (RuntimeException ex) {
			log.error("이벤트의 대상을 알 수 없어 대상 없이 기록한다: eventType={} eventId={} 원인={}", metadata.eventType(),
					metadata.eventId(), ex.getClass().getSimpleName());
			return null;
		}
	}

	// 이벤트의 내용(봉투 제외)을 그대로 싣는다. 싣지 못하면 표시만 남긴다. 원문은 이벤트 발행 기록에 이벤트 ID로 남아 있다.
	private String eventDetail(DomainEvent event, EventMetadata metadata) {
		try {
			JsonNode tree = jsonMapper.valueToTree(event);
			if (!(tree instanceof ObjectNode body)) {
				log.warn("객체가 아닌 이벤트라 내용 없이 기록한다: eventType={} eventId={}", metadata.eventType(),
						metadata.eventId());
				return null;
			}
			body.remove("metadata");
			if (body.isEmpty()) {
				return null;
			}
			String json = jsonMapper.writeValueAsString(body);
			// 크기를 먼저 본다. 큰 내용은 어차피 싣지 않으므로 전화번호를 찾느라 훑지 않는다.
			if (json.length() > AuditRecord.MAX_DETAIL_LENGTH) {
				log.warn("내용이 큰 이벤트라 내용 없이 기록한다: eventType={} eventId={} length={}", metadata.eventType(),
						metadata.eventId(), json.length());
				return DetailMarker.TRUNCATED.json();
			}
			String phoneField = phoneLikeField(body, "");
			if (phoneField != null) {
				log.error("전화번호처럼 보이는 값이 든 이벤트라 내용 없이 기록한다: eventType={} eventId={} field={}",
						metadata.eventType(), metadata.eventId(), phoneField);
				return DetailMarker.REDACTED.json();
			}
			return json;
		}
		catch (JacksonException ex) {
			// 예외 문구에는 이벤트의 값이 섞여 있을 수 있어 종류만 남긴다.
			log.error("이벤트의 내용을 읽을 수 없어 내용 없이 기록한다: eventType={} eventId={} 원인={}", metadata.eventType(),
					metadata.eventId(), ex.getClass().getSimpleName());
			return DetailMarker.UNREADABLE.json();
		}
	}

	// 문자열 값만 본다. 숫자로 실린 ID가 우연히 전화번호 모양이어도 걸리지 않는다.
	// 걸린 필드의 경로를 돌려준다(값은 돌려주지 않는다). 없으면 null.
	private static String phoneLikeField(JsonNode node, String path) {
		if (node.isString()) {
			return PhoneLikeText.found(node.asString()) ? path : null;
		}
		if (node.isObject()) {
			for (Map.Entry<String, JsonNode> property : node.properties()) {
				String found = phoneLikeField(property.getValue(), path.isEmpty() ? property.getKey()
						: path + "." + property.getKey());
				if (found != null) {
					return found;
				}
			}
		}
		else if (node.isArray()) {
			for (int i = 0; i < node.size(); i++) {
				String found = phoneLikeField(node.get(i), path + "[" + i + "]");
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	// DB(DATETIME(6))는 마이크로초까지만 저장한다. 저장한 값과 다시 읽은 값이 같도록 미리 맞춘다.
	private static Instant toStored(Instant instant) {
		return instant.truncatedTo(ChronoUnit.MICROS);
	}

	private static String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value.strip();
	}

}
