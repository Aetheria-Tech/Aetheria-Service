package com.serverbe.application.port.out.s3;

import com.serverbe.application.port.out.dto.ai.AiPromptCommand;

/**
 * @responsibility AI 추론 입력 파일을 오브젝트 스토리지에 올리고 지우는 아웃바운드 포트입니다.
 * @implSpec 직렬화 형식은 구현체가 정합니다. 포트는 {@link AiPromptCommand}라는 값만 주고받습니다.
 */
public interface S3AiInputPort {

    /**
     * @param taskId 작업 식별자. 파일 이름의 근거가 된다.
     * @param prompt 추론 요청 파라미터
     * @return 업로드된 객체의 URI
     */
    String uploadInputJson(String taskId, AiPromptCommand prompt);

    /**
     * @param s3Uri 삭제할 객체의 URI
     */
    void deleteInputFile(String s3Uri);
}
