package com.serverbe.infrastructure.config;

import com.serverbe.adapter.in.messaging.SseRedisMessageListener;
import com.serverbe.infrastructure.config.properties.SseProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * @responsibility 다중 서버 환경에서 SSE 알림 전파를 위한 Redis Pub/Sub 메시징 설정을 담당합니다.
 * @implNote 채널 이름을 {@link SseProperties}에서 읽습니다. 예전에는 여기서 문자열을 하드코딩하고
 * 발행측만 프로퍼티를 읽고 있었습니다. 두 값이 우연히 같아 동작했을 뿐, 환경별로 {@code sse.channel}을
 * 덮는 순간 발행과 구독이 어긋나 <b>에러 하나 없이</b> 실시간 알림이 죽습니다.
 */
@Configuration
@RequiredArgsConstructor
public class RedisPubSubConfig {

    private final SseRedisMessageListener sseRedisMessageListener;
    private final SseProperties sseProperties;

    @Bean
    public ChannelTopic sseTopic() {
        return new ChannelTopic(sseProperties.channel());
    }

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            // 💡 RedisConfig에서 생성한 ConnectionFactory를 여기서 자연스럽게 주입받아 사용합니다!
            RedisConnectionFactory connectionFactory) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(sseRedisMessageListener, sseTopic());

        return container;
    }
}
