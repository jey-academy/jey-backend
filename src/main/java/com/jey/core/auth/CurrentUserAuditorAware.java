package com.jey.core.auth;

import java.util.Optional;

import com.jey.core.auth.api.CurrentActor;
import org.springframework.data.domain.AuditorAware;
import org.springframework.stereotype.Component;

// 엔티티의 작성자(created_by)를 채운다. 로그인 없이 일어난 일(첫 관리자 생성, 배치 등)은 비워 둔다.
// 누구인지 알 수 없는 로그인 정보면 CurrentActor가 예외를 던져 저장이 멈춘다.
@Component
class CurrentUserAuditorAware implements AuditorAware<Long> {

	private final CurrentActor currentActor;

	CurrentUserAuditorAware(CurrentActor currentActor) {
		this.currentActor = currentActor;
	}

	@Override
	public Optional<Long> getCurrentAuditor() {
		return Optional.ofNullable(currentActor.get().idOrNull());
	}

}
