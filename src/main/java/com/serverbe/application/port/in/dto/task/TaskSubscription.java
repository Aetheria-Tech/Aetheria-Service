package com.serverbe.application.port.in.dto.task;

import com.serverbe.domain.model.task.vo.TaskStatus;

/**
 * @param taskId        구독 대상 작업 식별자
 * @param currentStatus 인가 시점의 작업 상태
 * @param resultArtId   완료된 경우 생성된 런닝 아트 식별자. 아니면 null.
 * @responsibility 구독 인가 결과와 <b>인가 시점의 상태 스냅샷</b>을 함께 전달합니다.
 * @implSpec 스냅샷이 필요한 이유는 알림 유실을 막기 위해서입니다. 클라이언트가 구독을 시작하기 직전에
 * 작업이 끝나 버리면 완료 이벤트가 이미 지나가 버려, 구독만 걸어 두면 영원히 아무것도 오지 않습니다.
 * 웹 어댑터는 {@link #isTerminal()}이 참이면 연결 직후 종료 이벤트를 즉시 재생합니다.
 * @implNote 이 방어 로직은 예전에 SSE 어댑터 내부에 있었습니다. 그대로 두면 인바운드 웹 어댑터가
 * 아웃바운드 포트({@code TaskQueryPort})를 직접 호출하게 되므로, 조회 자체는 애플리케이션 계층에
 * 남기고 결과만 넘기는 형태로 바꿨습니다.
 */
public record TaskSubscription(String taskId, TaskStatus currentStatus, Long resultArtId) {

    /**
     * @return 더 이상 상태가 바뀌지 않는 종결 상태면 true
     */
    public boolean isTerminal() {
        return currentStatus == TaskStatus.COMPLETED || currentStatus == TaskStatus.FAILED;
    }
}
