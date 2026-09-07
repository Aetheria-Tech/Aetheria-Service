package com.serverbe.application.port.out.dto.notification;

/**
 * @param taskId    대상 작업 식별자
 * @param eventName 이벤트 유형 ({@code COMPLETED} 또는 {@code FAILED})
 * @param data      결과 식별자 또는 에러 메시지
 * @responsibility 인스턴스 사이를 오가는 작업 알림 메시지의 형태를 정의합니다.
 * @implSpec <b>필드 이름이 곧 채널에 흐르는 JSON 키입니다.</b> 롤링 배포 중에는 구버전 인스턴스가
 * 발행한 메시지를 신버전이 받게 되므로, 이름을 바꾸면 그 순간 알림이 조용히 끊깁니다.
 * @implNote 발행측(아웃바운드 어댑터)과 수신측(인바운드 메시징 어댑터)이 함께 쓰기 때문에 포트 계층에
 * 둡니다. 어느 한쪽 어댑터 패키지에 두면 {@code adapter.in → adapter.out} 의존이 생깁니다.
 */
public record TaskNotificationMessage(
        String taskId,
        String eventName,
        String data
) {
}
