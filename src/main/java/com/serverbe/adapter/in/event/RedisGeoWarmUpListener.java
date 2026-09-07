package com.serverbe.adapter.in.event;

import com.serverbe.application.port.in.art.WarmUpGeoIndexUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * @responsibility 서버가 트래픽을 받을 준비를 마친 시점에 공간 인덱스 웜업을 시작시키는 인바운드 어댑터입니다.
 * @implSpec {@link ApplicationReadyEvent}를 고른 이유는 이 시점이 <b>트래픽 유입 직전</b>이기 때문입니다.
 * 더 이른 시점에는 데이터소스나 Redis 커넥션이 아직 준비되지 않았을 수 있습니다.
 * @implNote 스프링 생명주기 이벤트가 트리거일 뿐, HTTP 요청이나 SQS 메시지와 방향이 같은 진입점입니다.
 */
@Component
@RequiredArgsConstructor
public class RedisGeoWarmUpListener {

    private final WarmUpGeoIndexUseCase warmUpGeoIndexUseCase;

    @EventListener(ApplicationReadyEvent.class)
    public void syncGeoDataOnStartup() {
        warmUpGeoIndexUseCase.warmUpGeoIndex();
    }
}
