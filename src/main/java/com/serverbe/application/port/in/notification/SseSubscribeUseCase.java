package com.serverbe.application.port.in.notification;

import com.serverbe.application.port.in.dto.task.TaskSubscription;

/**
 * @responsibility AI 작업 상태 구독 요청의 <b>인가</b>를 담당하는 인바운드 포트입니다.
 * @implSpec 반환값은 인가 결과와 현재 상태 스냅샷입니다. 실제 연결 수립(SSE, WebSocket 등)은
 * 인바운드 어댑터의 몫이며, 이 포트는 전송 기술을 알지 않습니다.
 * @implNote 이전에는 {@code SseEmitter}(spring-webmvc)를 반환해 애플리케이션 계층이 서블릿 웹
 * 기술에 묶여 있었습니다.
 */
public interface SseSubscribeUseCase {

    /**
     * @param userId 구독을 요청하는 사용자의 ID (소유권 검증용)
     * @param taskId 실시간 알림을 수신할 AI 작업의 고유 ID
     * @return 인가된 구독 정보와 현재 상태 스냅샷
     * @throws com.serverbe.domain.exception.ai.AiException 작업이 없거나 요청자가 소유자가 아닌 경우
     */
    TaskSubscription subscribe(Long userId, String taskId);
}
