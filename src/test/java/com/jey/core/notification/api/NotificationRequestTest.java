package com.jey.core.notification.api;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import com.jey.core.shared.Actor;
import com.jey.core.shared.DomainEvent;
import com.jey.core.shared.EntityRef;
import com.jey.core.shared.EventMetadata;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationRequestTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-12T03:00:00Z"), ZoneId.of("Asia/Seoul"));

	private static final NotificationType PASS_ISSUED = PassNotification.PASS_ISSUED;

	private static final NotificationType PASS_EXPIRING = PassNotification.PASS_EXPIRING;

	private static final EntityRef PASS = EntityRef.of("pass", 5);

	@Test
	void 이벤트에서_만들면_대상과_이벤트_ID를_이벤트에서_가져온다() {
		PassIssued event = passIssued();

		NotificationRequest request = NotificationRequest.causedBy(event, PASS_ISSUED, Recipient.guardian(12));

		assertThat(request.type()).isSameAs(PASS_ISSUED);
		assertThat(request.recipient()).isEqualTo(Recipient.guardian(12));
		assertThat(request.about()).isEqualTo(PASS);
		assertThat(request.causeEventId()).isEqualTo(event.metadata().eventId());
	}

	// 실패한 이벤트는 같은 ID로 다시 온다. 리스너가 다시 돌아 같은 알림을 다시 맡겨도 같은 요청이어야
	// 보내는 쪽이 "이미 보낸 알림"으로 가릴 수 있다.
	@Test
	void 같은_이벤트로_같은_사람에게_같은_종류를_다시_만들면_같은_알림이다() {
		PassIssued event = passIssued();

		NotificationRequest first = NotificationRequest.causedBy(event, PASS_ISSUED, Recipient.guardian(12));
		NotificationRequest again = NotificationRequest.causedBy(event, PASS_ISSUED, Recipient.guardian(12));

		assertThat(again.isSameNotificationAs(first)).isTrue();
		// 이벤트에서 만든 요청끼리는 record의 비교도 같은 답을 낸다.
		assertThat(again).isEqualTo(first).hasSameHashCodeAs(first);
	}

	@Test
	void 받는_사람이나_종류나_이벤트가_다르면_다른_알림이다() {
		PassIssued event = passIssued();
		NotificationRequest request = NotificationRequest.causedBy(event, PASS_ISSUED, Recipient.guardian(12));

		assertThat(NotificationRequest.causedBy(event, PASS_ISSUED, Recipient.guardian(13))
				.isSameNotificationAs(request)).isFalse();
		assertThat(NotificationRequest.causedBy(event, PASS_EXPIRING, Recipient.guardian(12))
				.isSameNotificationAs(request)).isFalse();
		assertThat(NotificationRequest.causedBy(passIssued(), PASS_ISSUED, Recipient.guardian(12))
				.isSameNotificationAs(request)).isFalse();
	}

	// 종류는 이름으로 가린다. 같은 이름을 돌려주는 다른 객체여도 같은 알림이다.
	// (이름이 같은 종류 둘은 규칙 테스트가 막는다. 여기서는 가리는 기준이 이름이라는 것만 본다.)
	@Test
	void 종류는_객체가_아니라_이름으로_가린다() {
		PassIssued event = passIssued();
		NotificationType sameName = SameNameElsewhere.PASS_ISSUED;

		assertThat(NotificationRequest.causedBy(event, sameName, Recipient.guardian(12))
				.isSameNotificationAs(NotificationRequest.causedBy(event, PASS_ISSUED, Recipient.guardian(12)))).isTrue();
	}

	// 대상은 이벤트가 정하므로 같은 알림인지 가릴 때 보지 않는다. 이벤트가 대상을 적는 모양이 배포 사이에 달라져도
	// 다시 실행된 리스너의 요청은 같은 알림이어야 한다(달리 보면 두 번 나간다).
	@Test
	void 대상은_같은_알림인지_가릴_때_보지_않는다() {
		UUID eventId = passIssued().metadata().eventId();
		NotificationRequest request = new NotificationRequest(PASS_ISSUED, Recipient.guardian(12),
				EntityRef.of("pass", 5), eventId);
		NotificationRequest otherAbout = new NotificationRequest(PASS_ISSUED, Recipient.guardian(12),
				EntityRef.of("pass", 6), eventId);

		assertThat(otherAbout.isSameNotificationAs(request)).isTrue();
	}

	// 람다는 이름을 실행 중에 지어낼 수 있고(자유 문자열 칸이 된다), 종류의 규칙 테스트에도 보이지 않는다.
	@Test
	void enum이_아닌_종류로는_만들_수_없다() {
		PassIssued event = passIssued();
		NotificationType lambda = () -> "dongtanpass.pass-issued";
		NotificationType anonymous = new NotificationType() {
			@Override
			public String code() {
				return "dongtanpass.pass-issued";
			}
		};

		assertThatThrownBy(() -> NotificationRequest.causedBy(event, lambda, Recipient.guardian(12)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("enum");
		assertThatThrownBy(() -> NotificationRequest.causedBy(event, anonymous, Recipient.guardian(12)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("enum");
	}

	// 무엇에 대한 알림인지 모르면 보내는 쪽이 문구에 넣을 값을 찾을 수 없다. 조용히 대상 없는 알림이 되지 않게 한다.
	@Test
	void 대상이_없는_이벤트로는_만들_수_없다() {
		DomainEvent withoutSubject = new NoSubject(EventMetadata.create("core.test-happened", 1, 1L, Actor.none(), CLOCK));

		assertThatThrownBy(() -> NotificationRequest.causedBy(withoutSubject, PASS_ISSUED, Recipient.admins()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("core.test-happened");
	}

	@Test
	void 빠진_값이_있으면_만들_수_없다() {
		UUID eventId = passIssued().metadata().eventId();

		assertThatThrownBy(() -> new NotificationRequest(null, Recipient.admins(), PASS, eventId))
				.isInstanceOf(NullPointerException.class)
				.hasMessage("type");
		assertThatThrownBy(() -> new NotificationRequest(PASS_ISSUED, null, PASS, eventId))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new NotificationRequest(PASS_ISSUED, Recipient.admins(), null, eventId))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new NotificationRequest(PASS_ISSUED, Recipient.admins(), PASS, null))
				.isInstanceOf(NullPointerException.class);
	}

	// 이름은 2차에 문구(템플릿)와 이어진다. 형식이 틀린 이름은 요청을 만들 때 걸러낸다.
	@Test
	void 종류의_이름이_형식에_맞지_않으면_만들_수_없다() {
		UUID eventId = passIssued().metadata().eventId();

		for (InvalidName invalid : InvalidName.values()) {
			assertThatThrownBy(() -> new NotificationRequest(invalid, Recipient.admins(), PASS, eventId))
					.as(invalid.name())
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("형식");
		}
	}

	// 로그에 그대로 찍는다. 종류의 이름과 숫자 ID만 나온다.
	@Test
	void 글로_적으면_종류와_ID만_나온다() {
		PassIssued event = passIssued();

		NotificationRequest request = NotificationRequest.causedBy(event, PASS_ISSUED, Recipient.guardian(12));

		assertThat(request).hasToString("type=dongtanpass.pass-issued recipient=guardian:12 about=pass:5 event="
				+ event.metadata().eventId());
	}

	private static PassIssued passIssued() {
		return new PassIssued(EventMetadata.create("dongtanpass.pass-issued", 1, 1L, Actor.user(7), CLOCK), 5);
	}

	private enum PassNotification implements NotificationType {

		PASS_ISSUED("dongtanpass.pass-issued"), PASS_EXPIRING("dongtanpass.pass-expiring");

		private final String code;

		PassNotification(String code) {
			this.code = code;
		}

		@Override
		public String code() {
			return code;
		}

	}

	private enum SameNameElsewhere implements NotificationType {

		PASS_ISSUED;

		@Override
		public String code() {
			return "dongtanpass.pass-issued";
		}

	}

	private enum InvalidName implements NotificationType {

		MISSING(null), EMPTY(""), WITHOUT_MODULE("pass-issued"), UPPER_CASE("Dongtanpass.PassIssued"),
		PERSON_NAME("dongtanpass.김도윤 학생"), PHONE_NUMBER("dongtanpass.010-1234-5678");

		private final String code;

		InvalidName(String code) {
			this.code = code;
		}

		@Override
		public String code() {
			return code;
		}

	}

	private record PassIssued(EventMetadata metadata, long passId) implements DomainEvent {

		@Override
		public Optional<EntityRef> subject() {
			return Optional.of(EntityRef.of("pass", passId));
		}

	}

	private record NoSubject(EventMetadata metadata) implements DomainEvent {

		@Override
		public Optional<EntityRef> subject() {
			return Optional.empty();
		}

	}

}
