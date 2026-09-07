package com.serverbe.application.port.in.task;

import com.serverbe.application.port.in.dto.task.AiNotificationCommand;

/**
 * @responsibility 외부 AI 워커의 추론 결과 통보를 받아 작업 상태를 종결짓는 인바운드 포트입니다.
 * @implSpec 구현체는 <b>예외를 삼키지 않아야</b> 합니다. 메시지 브로커는 예외가 밖으로 나가는 것을
 * 재시도 신호로 읽으며, 삼키면 처리하지 못한 메시지가 조용히 사라집니다.
 */
public interface HandleAiNotificationUseCase {

    /**
     * @param command 추론 결과 통보
     */
    void handleNotification(AiNotificationCommand command);
}
