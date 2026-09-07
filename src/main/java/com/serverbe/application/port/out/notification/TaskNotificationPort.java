package com.serverbe.application.port.out.notification;

/**
 * @responsibility AI 작업의 종결 사실을 <b>모든 인스턴스에 전파</b>하는 아웃바운드 포트입니다.
 * @implSpec 구현체는 전파에 실패하더라도 호출자에게 예외를 전파하지 않아야 합니다. 알림 전파 실패가
 * 결과 저장 트랜잭션까지 되돌려서는 안 됩니다.
 * @implNote 예전에는 여기에 {@code SseEmitter subscribe(String)}도 있었습니다. 구독은 인바운드
 * 방향의 일이고 {@code SseEmitter}는 서블릿 웹 타입이라, 지금은 웹 어댑터의
 * {@code adapter.in.web.sse.SseEmitterRegistry}가 담당합니다.
 */
public interface TaskNotificationPort {

    /**
     * @param taskId      완료된 작업 식별자
     * @param resultArtId 생성된 런닝 아트 식별자
     */
    void notifyTaskCompleted(String taskId, String resultArtId);

    /**
     * @param taskId       실패한 작업 식별자
     * @param errorMessage 클라이언트에게 노출할 실패 사유
     */
    void notifyTaskFailed(String taskId, String errorMessage);
}
