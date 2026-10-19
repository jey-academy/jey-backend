#!/usr/bin/env bash
# 앱을 blue/green으로 바꿔 끼운다. SSM 문서 jey-prod-deploy가 root로 부른다.
#   deploy.sh <이미지 태그 = 커밋 SHA 40자>
# 순서: 비밀값으로 env 파일 → 쉬는 슬롯 기동 → readiness 대기 → Nginx 전환 → 확인 → 이전 슬롯 종료.
# 어느 단계든 실패하면 전환하지 않거나 되돌리고, 이전 슬롯이 그대로 요청을 받는다.
# 마지막 줄: "deployed <슬롯> <태그>"(종료 0) 또는 "kept <슬롯>: <이유>"(종료 1).
# 비밀값을 출력하지 않는다(set -x 금지, env 파일 내용을 찍지 않는다).
# 실패 시험: READY_TIMEOUT_SECONDS=1 deploy.sh <태그> → readiness를 못 기다려 kept로 끝난다.
set -euo pipefail

readonly BASE=/opt/jey
readonly RUNTIME="$BASE/runtime"
readonly SLOTS_ENV="$RUNTIME/slots.env"
readonly APP_ENV="$RUNTIME/app.env"
readonly STATE="$BASE/state/active-slot"
readonly UPSTREAM="$BASE/nginx/upstream/active.conf"
readonly PARAM_PATH=/jey/prod/
readonly READY_TIMEOUT_SECONDS="${READY_TIMEOUT_SECONDS:-180}"
# application-prod.yaml이 없으면 뜨지 않는 값. INITIAL_ADMIN_*는 계정이 생긴 뒤에는 없어도 된다.
readonly REQUIRED_PARAMS=(DB_URL DB_USERNAME DB_PASSWORD REDIS_HOST REDIS_PASSWORD AUTH_ALLOWED_ORIGINS
	AUTH_LOGIN_ATTEMPT_HMAC_KEY PHONE_ENCRYPTION_KEY_1 PHONE_HMAC_KEY QR_SIGNING_KEY_1)
export AWS_REGION="${AWS_REGION:-ap-northeast-2}"

current=""
target=""
previous_tag=""
previous_upstream=""

compose() {
	docker compose -p jey -f "$BASE/compose.yaml" --env-file "$SLOTS_ENV" "$@"
}

kept() {
	echo "kept ${current:-none}: $*"
	exit 1
}

port_of() {
	if [[ $1 == blue ]]; then echo 8081; else echo 8082; fi
}

tag_var_of() {
	if [[ $1 == blue ]]; then echo BLUE_TAG; else echo GREEN_TAG; fi
}

set_slot_tag() {
	local var
	var="$(tag_var_of "$1")"
	if grep -q "^${var}=" "$SLOTS_ENV"; then
		sed -i "s/^${var}=.*/${var}=$2/" "$SLOTS_ENV"
	else
		echo "${var}=$2" >>"$SLOTS_ENV"
	fi
}

# 새 슬롯을 세우다 실패했을 때: 로그를 남기고, 새 슬롯을 내리고, 태그를 되돌린다.
abandon_target() {
	compose logs --no-color --tail 80 "app-$target" || true
	compose stop -t 30 "app-$target" || true
	set_slot_tag "$target" "$previous_tag"
	kept "$1"
}

nginx_running() {
	[[ -n "$(compose ps -q --status running nginx)" ]]
}

apply_upstream() {
	if nginx_running; then
		compose exec -T nginx nginx -t -q && compose exec -T nginx nginx -s reload
	else
		compose up -d nginx
	fi
}

restore_upstream() {
	if [[ -n $previous_upstream ]]; then
		printf '%s\n' "$previous_upstream" >"$UPSTREAM"
		apply_upstream || true
	else
		rm -f "$UPSTREAM"
	fi
}

write_app_env() {
	local raw tmp name value missing=()
	raw="$(aws ssm get-parameters-by-path --path "$PARAM_PATH" --with-decryption \
		--query 'Parameters[].[Name,Value]' --output text)" || kept "Parameter Store를 읽지 못했다"
	tmp="$(mktemp "$RUNTIME/app.env.XXXXXX")"
	while IFS=$'\t' read -r name value; do
		[[ -z $name ]] && continue
		name="${name#"$PARAM_PATH"}"
		# env 파일은 따옴표·$·공백을 다르게 읽는다. 그런 값은 조용히 바뀌지 않게 거부한다.
		if [[ $value =~ [[:space:]\$\"\'\`\\#] ]]; then
			rm -f "$tmp"
			kept "파라미터 ${name}의 값에 env 파일로 넣을 수 없는 글자가 있다"
		fi
		printf '%s=%s\n' "$name" "$value" >>"$tmp"
	done <<<"$raw"
	for name in "${REQUIRED_PARAMS[@]}"; do
		grep -q "^${name}=." "$tmp" || missing+=("$name")
	done
	if ((${#missing[@]} > 0)); then
		rm -f "$tmp"
		kept "파라미터가 없다: ${missing[*]}"
	fi
	chmod 600 "$tmp"
	mv -f "$tmp" "$APP_ENV"
}

wait_ready() {
	local url deadline
	url="http://127.0.0.1:$(port_of "$target")/actuator/health/readiness"
	deadline=$((SECONDS + READY_TIMEOUT_SECONDS))
	until curl -fsS -o /dev/null --max-time 2 "$url"; do
		((SECONDS >= deadline)) && abandon_target "readiness가 ${READY_TIMEOUT_SECONDS}초 안에 통과하지 않았다"
		sleep 2
	done
}

health_through_nginx() {
	local tries=10
	until curl -fsS -o /dev/null --max-time 3 http://127.0.0.1/api/health; do
		((--tries > 0)) || return 1
		sleep 1
	done
}

main() {
	local tag="${1:-}"
	[[ $tag =~ ^[0-9a-f]{40}$ ]] || kept "이미지 태그가 커밋 SHA(40자) 형식이 아니다"

	exec 9>/run/lock/jey-deploy
	flock -n 9 || kept "다른 배포가 진행 중이다"

	if [[ ! -f $SLOTS_ENV ]] || ! grep -q '^ECR_REGISTRY=.' "$SLOTS_ENV"; then
		kept "slots.env에 ECR_REGISTRY가 없다"
	fi
	current="$(cat "$STATE" 2>/dev/null || true)"
	case "$current" in
	blue) target=green ;;
	green) target=blue ;;
	"") target=blue ;;
	*) kept "active-slot의 값이 blue/green이 아니다" ;;
	esac
	previous_tag="$(grep "^$(tag_var_of "$target")=" "$SLOTS_ENV" | cut -d= -f2- || true)"
	previous_upstream="$(cat "$UPSTREAM" 2>/dev/null || true)"

	write_app_env

	set_slot_tag "$target" "$tag"
	compose pull --quiet "app-$target" || {
		set_slot_tag "$target" "$previous_tag"
		kept "이미지를 받지 못했다: $tag"
	}
	compose up -d --no-deps --force-recreate "app-$target" || abandon_target "새 슬롯을 띄우지 못했다"
	wait_ready

	printf 'server app-%s:8080;\n' "$target" >"$UPSTREAM.tmp"
	mv -f "$UPSTREAM.tmp" "$UPSTREAM"
	if ! apply_upstream || ! health_through_nginx; then
		restore_upstream
		abandon_target "Nginx를 새 슬롯으로 옮기지 못했다"
	fi

	printf '%s\n' "$target" >"$STATE.tmp"
	mv -f "$STATE.tmp" "$STATE"
	if [[ -n $current ]]; then
		# reload 전에 받은 요청이 이전 슬롯에서 끝날 시간을 준 뒤 graceful 종료한다.
		sleep 5
		compose stop -t 30 "app-$current" || echo "이전 슬롯 app-$current를 멈추지 못했다(요청은 새 슬롯이 받는다)"
	fi
	docker image prune -f >/dev/null || true
	echo "deployed $target $tag"
}

main "$@"
