#!/usr/bin/env bats
# deploy.sh의 성공·실패 경로. docker·aws·curl·sleep·flock은 가짜(PATH 앞에 둔다)이고, /opt/jey 대신 임시 폴더를 쓴다.
# 실행: docker run --rm -v "$PWD:/repo" -w /repo bats/bats:1.11.0 infra/app/test

bats_require_minimum_version 1.5.0

SHA=0123456789abcdef0123456789abcdef01234567
OLD=89abcdef0123456789abcdef0123456789abcdef

setup() {
	export JEY_BASE="$BATS_TEST_TMPDIR/jey" JEY_LOCK_FILE="$BATS_TEST_TMPDIR/lock" FAKE="$BATS_TEST_TMPDIR/fake"
	mkdir -p "$JEY_BASE"/{runtime,state,nginx/upstream} "$FAKE/bin"
	printf 'ECR_REGISTRY=registry.example\nBLUE_TAG=\nGREEN_TAG=\n' >"$JEY_BASE/runtime/slots.env"
	echo "old-app-env" >"$JEY_BASE/runtime/app.env"
	params ok
	fakes
	export PATH="$FAKE/bin:$PATH"
	SCRIPT="$BATS_TEST_DIRNAME/../bin/deploy.sh"
}

# 파라미터 저장소의 응답(이름<TAB>값). ok = 필수 값이 모두 있다.
params() {
	: >"$FAKE/params"
	local name
	for name in DB_URL DB_USERNAME DB_PASSWORD REDIS_HOST REDIS_PASSWORD AUTH_ALLOWED_ORIGINS \
		AUTH_LOGIN_ATTEMPT_HMAC_KEY PHONE_ENCRYPTION_KEY_1 PHONE_HMAC_KEY QR_SIGNING_KEY_1; do
		printf '/jey/prod/%s\tvalue-%s\n' "$name" "$name" >>"$FAKE/params"
	done
}

fakes() {
	cat >"$FAKE/bin/docker" <<'EOF'
#!/usr/bin/env bash
echo "docker $*" >>"$FAKE/calls"
[[ $1 == image ]] && exit 0
shift 7 # compose -p jey -f <파일> --env-file <파일>
case "$1" in
ps) [[ -f $FAKE/nginx_running ]] && echo nginx-id; exit 0 ;;
up) [[ " $* " == *" nginx "* ]] && touch "$FAKE/nginx_running"; exit 0 ;;
pull) exit "${FAKE_PULL_RC:-0}" ;;
logs) echo "APP-LOG-LINE jdbc:mysql://db.internal.example"; exit 0 ;;
stop) exit 0 ;;
exec)
	if [[ " $* " == *" -t -q"* ]]; then exit "${FAKE_NGINX_T_RC:-0}"; fi
	n=$(($(cat "$FAKE/reloads" 2>/dev/null || echo 0) + 1)); echo "$n" >"$FAKE/reloads"
	[[ $n == "${FAKE_RELOAD_FAIL_ON:-0}" ]] && exit 1
	exit 0 ;;
esac
exit 0
EOF
	cat >"$FAKE/bin/aws" <<'EOF'
#!/usr/bin/env bash
cat "$FAKE/params"
EOF
	cat >"$FAKE/bin/curl" <<'EOF'
#!/usr/bin/env bash
url="${*: -1}"
case "$url" in
*readiness*) exit "${FAKE_READY_RC:-0}" ;;
*/api/health) exit "${FAKE_HEALTH_RC:-0}" ;;
esac
exit 0
EOF
	printf '#!/bin/sh\nexit 0\n' >"$FAKE/bin/sleep"
	printf '#!/bin/sh\nexit 0\n' >"$FAKE/bin/flock"
	chmod +x "$FAKE/bin/"*
}

# blue가 요청을 받고 있는 상태
serving_blue() {
	echo blue >"$JEY_BASE/state/active-slot"
	echo 'server app-blue:8080;' >"$JEY_BASE/nginx/upstream/active.conf"
	sed -i "s/^BLUE_TAG=.*/BLUE_TAG=$OLD/" "$JEY_BASE/runtime/slots.env"
	touch "$FAKE/nginx_running"
}

last_line() { printf '%s\n' "$output" | tail -n 1; }

@test "첫 배포는 blue로 가고 Nginx·상태·태그를 맞춘다" {
	run bash "$SCRIPT" "$SHA"
	[ "$status" -eq 0 ]
	[ "$(last_line)" = "deployed blue $SHA" ]
	[ "$(cat "$JEY_BASE/nginx/upstream/active.conf")" = "server app-blue:8080;" ]
	[ "$(cat "$JEY_BASE/state/active-slot")" = blue ]
	grep -q "^BLUE_TAG=$SHA$" "$JEY_BASE/runtime/slots.env"
}

@test "readiness를 못 지나면 전환하지 않고 태그를 되돌리며, 앱 로그는 출력하지 않고 서버 파일에 남긴다" {
	serving_blue
	FAKE_READY_RC=1 READY_TIMEOUT_SECONDS=0 run bash "$SCRIPT" "$SHA"
	[ "$status" -eq 1 ]
	[[ "$(last_line)" == "kept blue: readiness"* ]]
	[ "$(cat "$JEY_BASE/nginx/upstream/active.conf")" = "server app-blue:8080;" ]
	grep -q '^GREEN_TAG=$' "$JEY_BASE/runtime/slots.env"
	[[ "$output" != *APP-LOG-LINE* ]]
	grep -q APP-LOG-LINE "$JEY_BASE/state/failed-app-green.log"
	[ "$(stat -c %a "$JEY_BASE/state/failed-app-green.log")" = 600 ]
}

@test "전환 뒤 확인이 실패하면 이전 슬롯으로 되돌리고 새 슬롯을 멈춘다" {
	serving_blue
	FAKE_HEALTH_RC=1 run bash "$SCRIPT" "$SHA"
	[ "$status" -eq 1 ]
	[[ "$(last_line)" == "kept blue:"* ]]
	[ "$(cat "$JEY_BASE/nginx/upstream/active.conf")" = "server app-blue:8080;" ]
	grep -q "compose .* stop -t 30 app-green" "$FAKE/calls"
}

@test "되돌리는 reload까지 실패하면 새 슬롯을 멈추지 않고 failed로 알린다" {
	serving_blue
	FAKE_HEALTH_RC=1 FAKE_RELOAD_FAIL_ON=2 run bash "$SCRIPT" "$SHA"
	[ "$status" -eq 1 ]
	[[ "$(last_line)" == "failed green:"* ]]
	run ! grep -q "stop -t 30 app-green" "$FAKE/calls"
	# Nginx는 green 설정 그대로 돈다. 파일도 그것을 가리켜야 다음 배포가 green을 지금 슬롯으로 본다.
	[ "$(cat "$JEY_BASE/nginx/upstream/active.conf")" = "server app-green:8080;" ]
}

@test "지금 슬롯은 상태 파일이 아니라 Nginx가 가리키는 곳이다" {
	serving_blue
	echo green >"$JEY_BASE/state/active-slot" # 어긋난 상태 파일
	run bash "$SCRIPT" "$SHA"
	[ "$status" -eq 0 ]
	[ "$(last_line)" = "deployed green $SHA" ]
	grep -q "force-recreate app-green" "$FAKE/calls"
	run ! grep -q "force-recreate app-blue" "$FAKE/calls"
}

@test "전환 뒤 예상하지 못한 오류도 마지막 줄로 알리고, 요청을 받는 슬롯을 적는다" {
	serving_blue
	rm -rf "$JEY_BASE/state"
	touch "$JEY_BASE/state" # 상태 파일을 쓸 수 없게
	run bash "$SCRIPT" "$SHA"
	[ "$status" -eq 1 ]
	[[ "$(last_line)" == "failed green:"* ]]
}

@test "첫 배포에서 전환이 실패해도 Nginx 설정 파일을 지우지 않는다" {
	FAKE_HEALTH_RC=1 run bash "$SCRIPT" "$SHA"
	[ "$status" -eq 1 ]
	[[ "$(last_line)" == "kept none:"* ]]
	[ -f "$JEY_BASE/nginx/upstream/active.conf" ]
}

@test "값에 공백이 있는 파라미터가 있으면 app.env를 바꾸지 않는다" {
	printf '/jey/prod/DB_PASSWORD\tbad value\n' >>"$FAKE/params"
	run bash "$SCRIPT" "$SHA"
	[ "$status" -eq 1 ]
	[[ "$(last_line)" == "kept none: 파라미터 DB_PASSWORD"* ]]
	[ "$(cat "$JEY_BASE/runtime/app.env")" = old-app-env ]
}

@test "필수 파라미터가 없으면 app.env를 바꾸지 않는다" {
	grep -v QR_SIGNING_KEY_1 "$FAKE/params" >"$FAKE/p" && mv "$FAKE/p" "$FAKE/params"
	run bash "$SCRIPT" "$SHA"
	[ "$status" -eq 1 ]
	[[ "$(last_line)" == *"QR_SIGNING_KEY_1"* ]]
	[ "$(cat "$JEY_BASE/runtime/app.env")" = old-app-env ]
}

@test "태그가 커밋 SHA 형식이 아니면 아무것도 하지 않는다" {
	run bash "$SCRIPT" latest
	[ "$status" -eq 1 ]
	[[ "$(last_line)" == "kept none: 이미지 태그"* ]]
	[ ! -f "$FAKE/calls" ]
}
