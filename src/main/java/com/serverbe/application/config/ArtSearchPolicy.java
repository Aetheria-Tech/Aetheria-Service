package com.serverbe.application.config;

/**
 * @param maxRadius      주변 검색으로 허용하는 최대 반경 (km)
 * @param maxResultLimit 한 번의 주변 검색이 돌려주는 최대 건수
 * @responsibility 런닝 아트 주변 검색의 한계를 정의합니다.
 */
public record ArtSearchPolicy(double maxRadius, int maxResultLimit) {
}
