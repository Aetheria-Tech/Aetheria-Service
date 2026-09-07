package com.serverbe.adapter.out.notification;

import com.serverbe.application.port.out.notification.AlertNotificationPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Collections;
import java.util.Map;

/**
 * @responsibility 운영 알림을 Discord 웹훅으로 발송합니다.
 * @implSpec Fire-and-Forget입니다. {@code subscribe()}로 발행만 하고 응답을 기다리지 않습니다.
 * 알림 전송이 본래 요청 처리를 지연시키거나 실패시켜서는 안 됩니다.
 * @implNote 웹훅 URL이 비어 있으면 조용히 넘어갑니다. 로컬·테스트 환경에서 URL 없이도 서버가 떠야 합니다.
 */
@Slf4j
@Component
public class DiscordAlertAdapter implements AlertNotificationPort {

    private final String discordWebhookUrl;
    private final WebClient webClient;

    public DiscordAlertAdapter(
            WebClient.Builder webClientBuilder,
            @Value("${notification.discord.webhook-url}") String discordWebhookUrl
    ) {
        this.webClient = webClientBuilder.build();
        this.discordWebhookUrl = discordWebhookUrl;
    }

    @Override
    public void sendAlert(String message) {
        if (discordWebhookUrl == null || discordWebhookUrl.isBlank()) {
            log.warn("웹훅 URL이 설정되지 않아 알림을 보낼 수 없습니다.");
            return;
        }

        try {
            Map<String, String> payload = Collections.singletonMap("content", message);

            // WebClient를 이용한 비동기 POST 요청
            webClient.post()
                    .uri(discordWebhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(Void.class) // 응답 본문은 필요 없으므로 Void
                    .subscribe(             // subscribe()를 호출해야 실제 비동기 요청이 실행됨 (Fire-and-Forget)
                            success -> log.info("디스코드 알림 전송 완료"),
                            error -> log.error("디스코드 알림 전송 중 오류 발생: {}", error.getMessage())
                    );

        } catch (Exception e) {
            log.error("디스코드 알림 페이로드 생성 중 오류 발생: {}", e.getMessage());
        }
    }
}
