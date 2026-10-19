// CloudFront Function jey-prod-viewer-request (cloudfront-js-2.0, 뷰어 요청).
// 배포의 두 동작(기본 = S3 프론트, /api/* = 앱)에 같이 붙인다. 도메인은 코드에 적지 않는다(요청의 host를 쓴다).
// 1) host가 www.로 시작하지 않는 요청(상위 도메인, 배포의 기본 도메인)은 같은 경로·쿼리로 www.<host>에 301.
// 2) www의 프론트 경로(/students/1 처럼 확장자가 없는 것)는 /index.html로 바꿔 S3에 묻는다(SPA).
//    /api/는 건드리지 않는다(끝 슬래시가 없는 /api는 API가 아니라 프론트 경로로 본다). 배포의 오류 페이지 설정(403/404 → index.html)을 쓰지 않는 이유다 —
//    그 설정은 /api/*의 404 Problem Details까지 HTML로 덮는다.
// 시험: node infra/cloudfront/run-tests.mjs (이벤트 test-events/*.json, 기대값 expected.json. CI가 돌린다)
function handler(event) {
	var request = event.request;
	var host = request.headers.host ? request.headers.host.value : '';

	if (host !== '' && host.indexOf('www.') !== 0) {
		return {
			statusCode: 301,
			statusDescription: 'Moved Permanently',
			headers: {
				location: { value: 'https://www.' + host + request.uri + queryString(request.querystring) },
			},
		};
	}

	if (request.uri.indexOf('/api/') === 0) {
		return request;
	}
	var lastSegment = request.uri.substring(request.uri.lastIndexOf('/') + 1);
	if (lastSegment.indexOf('.') === -1) {
		request.uri = '/index.html';
	}
	return request;
}

// 받은 쿼리를 그대로 다시 적는다(값은 받은 모양 그대로, 같은 이름이 여럿이면 모두 그 순서로).
// 서로 다른 이름 사이의 순서는 런타임이 정한다(받은 순서와 다를 수 있다 — 쿼리에서 뜻이 없다).
function queryString(querystring) {
	var parts = [];
	Object.keys(querystring).forEach(function (name) {
		var entry = querystring[name];
		var values = entry.multiValue ? entry.multiValue : [entry];
		values.forEach(function (item) {
			parts.push(name + '=' + item.value);
		});
	});
	return parts.length > 0 ? '?' + parts.join('&') : '';
}
