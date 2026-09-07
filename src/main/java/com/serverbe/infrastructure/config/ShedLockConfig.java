package com.serverbe.infrastructure.config;

import com.serverbe.infrastructure.config.properties.RedisProperties;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * 분산 환경에서 스케줄러의 중복 실행을 방지하기 위한 ShedLock 설정 클래스.
 * <p>
 * <b>배경:</b><br>
 * 현재 시스템은 고가용성(High Availability)을 위해 AWS ECS(Fargate) 상에서 다수의 인스턴스로 운영됩니다.
 * Spring의 {@code @Scheduled}는 기본적으로 각 서버 인스턴스마다 독립적으로 실행되므로,
 * 동일한 시간에 여러 서버가 같은 배치 작업을 수행하는 중복 실행 문제가 발생할 수 있습니다.
 * </p>
 * <p>
 * <b>해결책:</b><br>
 * 공유 자원인 <b>Redis</b>를 락 저장소(Lock Provider)로 활용하여, 여러 서버 중 단 하나의 인스턴스만
 * 특정 스케줄링 작업을 점유할 수 있도록 제어합니다.
 * </p>
 *
 * @implNote
 * - {@link EnableSchedulerLock}: 스케줄러 락 기능을 활성화합니다.
 * {@code defaultLockAtMostFor}는 개별 {@code @SchedulerLock}에서 설정이 누락되었을 때 적용될 안전장치 시간입니다.
 * - {@link RedisLockProvider}: Redis Connection Factory를 사용하여 락 정보를 Redis에 저장합니다.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
@RequiredArgsConstructor
public class ShedLockConfig {

    private final RedisProperties redisProperties;

    /**
     * ShedLock이 사용할 락 공급자(LockProvider)를 빈으로 등록합니다.
     * <p>
     * <b>동작 원리:</b><br>
     * 스케줄러 실행 시 지정된 {@code name}을 키로 사용하여 Redis에 락을 생성합니다.
     * 이미 동일한 키가 존재하면 다른 서버가 작업을 수행 중인 것으로 판단하고 현재 서버는 작업을 건너뜁니다.
     * </p>
     *
     * @param connectionFactory 기존 시스템에서 사용 중인 Redis 연결 팩토리
     * @return Redis 기반의 락 공급자 객체
     */
    @Bean
    public LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
        // redisProperties에서 정의된 shedlock prefix를 사용하여 Redis 키를 관리합니다.
        return new RedisLockProvider(connectionFactory, redisProperties.shedlock().prefix());
    }
}