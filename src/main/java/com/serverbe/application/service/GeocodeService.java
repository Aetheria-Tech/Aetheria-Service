package com.serverbe.application.service;

import com.serverbe.application.port.in.geocode.GeocodeAddressUseCase;
import com.serverbe.application.port.out.dto.geocoding.GeocodeResult;
import com.serverbe.application.port.out.geocode.GeocodePort;
import com.serverbe.domain.model.address.Address;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * {@link GeocodeAddressUseCase}의 구현체.
 * <p>
 * 입력받은 주소를 도메인 값 객체 {@link Address}로 변환해 유효성을 검증한 뒤,
 * 아웃바운드 포트 {@link GeocodePort}에 위경도 변환을 위임합니다.
 * </p>
 */
@Service
@RequiredArgsConstructor
public class GeocodeService implements GeocodeAddressUseCase {

    private final GeocodePort geocodePort;

    @Override
    public Mono<GeocodeResult> geocode(String address) {
        Address validatedAddress = new Address(address); // 생성자에서 형식 유효성을 스스로 검증

        return geocodePort.geocode(validatedAddress.value());
    }
}
