/**
 * 알림을 맡기는 창구 (ADR-0029). 다른 모듈은 {@link com.jey.core.notification.api.NotificationSender}에
 * {@link com.jey.core.notification.api.NotificationRequest}를 맡기고, 어떻게 보내는지는 모른다(1차: 보내지 않고 기록만 한다).
 * 알림의 종류는 맡기는 모듈이 {@link com.jey.core.notification.api.NotificationType}을 구현한 enum으로 선언한다.
 * 요청에는 ID만 싣는다. 전화번호, 이름, 문구는 싣지 않는다.
 */
@org.springframework.modulith.NamedInterface("notification")
package com.jey.core.notification.api;
