/**
 * 구글 스프레드시트를 읽고 쓰는 창구 (ADR-0047). 다른 모듈은 {@link com.jey.core.sheets.api.Spreadsheets}로 읽고, 칸에 쓰고,
 * 줄을 붙인다. 어느 파일·탭·칸에 무엇을 쓸지, 실패를 어떻게 다시 할지는 쓰는 모듈이 정한다.
 */
@org.springframework.modulith.NamedInterface("sheets")
package com.jey.core.sheets.api;
