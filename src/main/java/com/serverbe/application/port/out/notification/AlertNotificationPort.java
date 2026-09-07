package com.serverbe.application.port.out.notification;

/**
 * @responsibility 시스템 장애·복구를 운영자에게 알리는 아웃바운드 포트입니다.
 * @implSpec 전송 채널(Discord, Slack, 이메일 등)은 어댑터가 정합니다. 애플리케이션은 "무엇을 알릴지"만
 * 결정하고 "어떻게 보낼지"는 모릅니다.
 * @implNote 구현체는 알림 전송 실패를 호출자에게 전파하지 않아야 합니다. 알림이 안 갔다고 해서
 * 정작 알리려던 본래 처리까지 실패하면 곤란합니다.
 */
public interface AlertNotificationPort {

    /**
     * @param message 운영자에게 전달할 메시지
     */
    void sendAlert(String message);
}
