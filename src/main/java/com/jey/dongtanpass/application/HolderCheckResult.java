package com.jey.dongtanpass.application;

/**
 * 본인 미리 확인의 결과. 이름과 학부모 번호를 따로 답한다(틀린 쪽을 등록 폼에서 바로 고치게).
 */
public record HolderCheckResult(boolean nameMatches, boolean phoneMatches) {

}
