package com.serverbe.application.config;

/**
 * @param timeoutThresholdMinutes 이 시간이 지나도 콜백이 오지 않으면 좀비 작업으로 본다 (분)
 * @responsibility AI 작업을 회수 대상으로 판단하는 기준 시간을 정의합니다.
 */
public record TaskTimeoutPolicy(int timeoutThresholdMinutes) {
}
