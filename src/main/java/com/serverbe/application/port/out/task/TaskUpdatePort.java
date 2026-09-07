package com.serverbe.application.port.out.task;


import com.serverbe.domain.model.task.AiTask;

import java.util.List;

/**
 * @responsibility AI 추론 작업(AiTask)의 상태 변경 및 신규 생성을 영속성 계층(DB)에 반영하는 아웃바운드 포트
 */
public interface TaskUpdatePort {

    /**
     * @responsibility 순수 도메인 모델(Record)의 변경된 상태를 저장하거나 갱신합니다.
     * @param aiTask 저장 또는 갱신할 대상 도메인 객체 (불변 객체이므로 변경된 새 인스턴스가 넘어옴)
     */
    AiTask save(AiTask aiTask);

    /**
     * @responsibility 여러 작업을 한 번의 UPDATE 로 실패 처리하고 진행 슬롯을 반납합니다.
     * @param taskIds 실패 처리할 작업 ID 목록
     * @param errorMessage DB 에 기록할 실패 사유
     * @return 실제로 갱신된 행 수
     * @implNote 좀비 정리처럼 다수의 작업을 동일한 사유로 한꺼번에 종결시킬 때 사용합니다.
     * 건별 {@link #save(AiTask)} 를 반복하면 작업 수에 비례해 문장이 늘어납니다.
     */
    int markFailedInBulk(List<String> taskIds, String errorMessage);
}