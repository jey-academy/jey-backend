// viewer-request.js를 test-events/*.json으로 돌려 expected.json과 맞춘다. 실행: node infra/cloudfront/run-tests.mjs
// CloudFront 런타임(cloudfront-js-2.0)이 아니라 Node에서 돈다. 함수가 쓰는 것(객체, 문자열, Object.keys)은 둘이 같다.
// 배포 전 최종 확인은 CloudShell의 aws cloudfront test-function(런북 8.6)이다.
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isDeepStrictEqual } from 'node:util';

const here = dirname(fileURLToPath(import.meta.url));
const handler = new Function(`${readFileSync(join(here, 'viewer-request.js'), 'utf8')}\nreturn handler;`)();
const eventsDir = join(here, 'test-events');
const expected = JSON.parse(readFileSync(join(eventsDir, 'expected.json'), 'utf8'));
const events = readdirSync(eventsDir).filter((f) => f.endsWith('.json') && f !== 'expected.json').map((f) => f.slice(0, -5));

let failures = 0;
for (const name of new Set([...events, ...Object.keys(expected)])) {
	const want = expected[name];
	if (!want || !events.includes(name)) {
		console.log(`FAIL ${name}: 이벤트 파일과 기대값이 짝이 맞지 않는다`);
		failures++;
		continue;
	}
	const result = handler(JSON.parse(readFileSync(join(eventsDir, `${name}.json`), 'utf8')));
	const got = {
		statusCode: result.statusCode,
		location: result.headers?.location?.value,
		uri: result.uri,
		querystring: result.querystring,
	};
	const wrong = Object.keys(want).filter((key) => !isDeepStrictEqual(got[key], want[key]));
	if (wrong.length > 0) {
		console.log(`FAIL ${name}: ${wrong.map((k) => `${k} ${JSON.stringify(got[k])} (기대 ${JSON.stringify(want[k])})`).join(', ')}`);
		failures++;
	} else {
		console.log(`PASS ${name}`);
	}
}
process.exit(failures > 0 ? 1 : 0);
