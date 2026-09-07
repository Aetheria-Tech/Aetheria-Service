package com.serverbe.application.port.out.dto.ai;

/**
 * @param latitude    러닝 시작 지점의 위도
 * @param longitude   러닝 시작 지점의 경도
 * @param shape       생성할 아트 모양. 값이 없으면 빈 문자열이다.
 * @param proficiency 러닝 숙련도 이름. 값이 없으면 {@code BEGINNER}다.
 * @responsibility AI 추론 요청에 실어 보낼 파라미터를 타입 있는 형태로 정의합니다.
 * @implSpec <b>필드 이름이 곧 JSON 키</b>이며 SageMaker 추론 스크립트가 그 키로 값을 읽습니다.
 * 이름을 바꾸면 추론이 조용히 기본값으로 동작하므로, 변경 시 추론 스크립트를 함께 고쳐야 합니다.
 * @implNote 이전에는 {@code AiGenerationService}가 {@code ObjectMapper}로 {@code Map}을 직접
 * 직렬화했습니다. 직렬화는 저장소의 표현 형식이므로 어댑터의 일이고, 애플리케이션은 값만 넘깁니다.
 */
public record AiPromptCommand(
        double latitude,
        double longitude,
        String shape,
        String proficiency
) {
}
