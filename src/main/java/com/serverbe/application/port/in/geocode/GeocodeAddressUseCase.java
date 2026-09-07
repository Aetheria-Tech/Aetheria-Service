package com.serverbe.application.port.in.geocode;

import com.serverbe.application.port.out.dto.geocoding.GeocodeResult;
import reactor.core.publisher.Mono;

/**
 * 주소 문자열을 위경도 좌표로 변환하는 유스케이스 (Inbound Port).
 * <p>
 * 클라이언트가 단독으로 주소 검증/좌표 변환 기능을 사용하고자 할 때 호출하는 비즈니스 진입점입니다.
 * 인바운드 어댑터(예: {@code GeocodeController})는 이 포트만 알면 되고, 실제로 어떤 외부
 * 지오코딩 API({@link com.serverbe.application.port.out.geocode.GeocodePort}의 구현체)를
 * 사용하는지는 알 필요가 없습니다.
 * </p>
 */
public interface GeocodeAddressUseCase {

    /**
     * 전달받은 주소 문자열의 유효성을 검증하고, 유효하다면 위경도 좌표로 변환합니다.
     *
     * @param address 변환하고자 하는 주소 문자열
     * @return 지오코딩 결과 정보를 담고 있는 비동기 스트림 {@link Mono<GeocodeResult>}
     */
    Mono<GeocodeResult> geocode(String address);
}
