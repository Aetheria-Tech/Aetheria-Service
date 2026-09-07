package com.serverbe.domain.util;

import com.serverbe.domain.exception.server.ServerErrorCode;
import com.serverbe.domain.exception.server.ServerException;

/**
 * Google Encoded Polyline Format 문자열을 파싱하고 공간 연산을 수행하는 유틸리티 클래스.
 * <p>
 * <b>최적화 포인트:</b><br>
 * 수많은 위경도 좌표를 모두 메모리(List 등)에 적재하지 않고,
 * 문자열을 순차적으로 해독하면서 즉시 거리를 누적 계산하여 메모리 사용량을 최소화(O(1) 공간 복잡도)합니다.
 * </p>
 * <p>
 * <b>패키지 위치에 대하여:</b> 이 유틸리티는 특정 프레임워크나 외부 I/O에 의존하지 않는 순수 알고리즘(좌표
 * 디코딩 및 거리 계산)이므로 인프라(infrastructure) 계층이 아닌 도메인(domain) 계층에 위치합니다.
 * 애플리케이션 서비스({@code RunningArtService})가 이 유틸리티를 안전하게 사용할 수 있으려면,
 * 헥사고날 아키텍처의 의존성 규칙(바깥쪽 계층 → 안쪽 계층)에 따라 인프라 계층이 아닌 도메인 계층에
 * 있어야 하기 때문입니다.
 * </p>
 */
public final class PolylineUtils {

    /** 위경도 좌표 인코딩/디코딩에 사용되는 정밀도 계수 (소수점 5자리) */
    private static final double PRECISION = 1e5; // 100,000

    /** 지구의 평균 반지름 (미터 단위) */
    private static final double EARTH_RADIUS_METERS = 6371000.0;

    private PolylineUtils() {
        throw new ServerException(ServerErrorCode.UTILITY_CLASS, "Utility class cannot be instantiated");
    }

    /**
     * 폴리라인 해독 결과인 메타데이터를 담는 불변 레코드.
     *
     * @param startLat 시작점의 위도 (Latitude)
     * @param startLon 시작점의 경도 (Longitude)
     * @param totalDistanceMeters 전체 폴리라인 경로의 누적 거리 (미터 단위)
     */
    public record PolylineMetadata(
            double startLat,
            double startLon,
            double totalDistanceMeters
    ) {
    }

    /**
     * Encoded Polyline 문자열을 끝까지 해독하여 런닝 코스의 첫 번째 좌표와 전체 누적 거리를 계산합니다.
     *
     * @param encoded Google Encoded Polyline Format 형식의 문자열
     * @return 파싱된 시작 좌표 및 총 거리가 포함된 {@link PolylineMetadata} 객체
     * @throws IllegalArgumentException 인코딩된 문자열이 null이거나 비어있는 경우
     * @throws ServerException 문자열이 손상되었거나 유효한 좌표가 하나도 없는 경우
     */
    public static PolylineMetadata extractMetadata(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            throw new ServerException(ServerErrorCode.POLYLINE_PARSE_ERROR);
        }

        int len = encoded.length();
        int[] index = {0}; // 참조를 통해 decodeInt 메서드와 현재 위치 상태를 공유하기 위한 배열

        int currentLatE5 = 0;
        int currentLngE5 = 0;

        Double startLat = null;
        Double startLon = null;

        double prevLat = 0.0;
        double prevLon = 0.0;
        double totalDistance = 0.0;

        while (index[0] < len) {
            // Delta 값을 누적하여 실제 좌표를 복원
            currentLatE5 += decodeInt(encoded, index);
            currentLngE5 += decodeInt(encoded, index);

            double lat = currentLatE5 / PRECISION;
            double lon = currentLngE5 / PRECISION;

            if (startLat == null) {
                // 첫 번째 좌표를 시작점(Start Point)으로 저장
                startLat = lat;
                startLon = lon;
            } else {
                // 이전 좌표와 현재 좌표 사이의 거리를 계산하여 누적
                totalDistance += calculateHaversineDistance(prevLat, prevLon, lat, lon);
            }

            prevLat = lat;
            prevLon = lon;
        }

        // 루프를 다 돌았는데도 시작 좌표가 null이라면, 유효한 좌표가 없는 손상된 문자열입니다.
        // 이 처리를 통해 Record의 원시 타입(double)으로 Auto-unboxing 될 때 발생하는 NPE를 사전에 방어합니다.
        if (startLat == null || startLon == null) {
            throw new ServerException(
                    ServerErrorCode.INTERNAL_SERVER_ERROR,
                    "Invalid encoded polyline string: no points found"
            );
        }

        return new PolylineMetadata(startLat, startLon, totalDistance);
    }

    /**
     * 폴리라인 알고리즘의 핵심 로직으로, 가변 길이 인코딩(Variable-length encoding)된 문자열에서 하나의 정수형 Delta 값을 뽑아냅니다.
     * <p>
     * ASCII 문자에서 63을 빼고, 비트 시프트 연산을 통해 원래의 음수/양수 값을 복원합니다.
     * </p>
     *
     * @param encoded 인코딩된 원본 문자열
     * @param index 현재 읽고 있는 문자열의 인덱스 위치를 담은 배열 (상태 공유용)
     * @return 복원된 좌표 Delta 값 (정수형)
     * @throws ServerException 문자열이 비정상적으로 잘려있는 경우
     */
    private static int decodeInt(String encoded, int[] index) {
        int result = 0;
        int shift = 0;
        int b;
        do {
            if (index[0] >= encoded.length()) {
                throw new ServerException(
                        ServerErrorCode.INTERNAL_SERVER_ERROR,
                        "Truncated polyline string"
                );
            }

            b = encoded.charAt(index[0]++) - 63;
            result |= (b & 0x1f) << shift;
            shift += 5;
        } while (b >= 0x20); // 0x20(32) 이상이면 다음 청크가 존재함을 의미

        // 마지막 비트를 확인하여 음수 처리를 수행 (Two's complement 역연산)
        return ((result & 1) != 0 ? ~(result >> 1) : (result >> 1));
    }

    /**
     * 하버사인(Haversine) 공식을 사용하여 구면 좌표계(지구) 상의 두 위경도 좌표 사이의 최단 거리(대원 거리)를 계산합니다.
     * <p>
     * <b>Haversine Formula:</b><br>
     * $a=\sin^2(\frac{\Delta lat}{2})+\cos(lat_1)\cdot\cos(lat_2)\cdot\sin^2(\frac{\Delta lon}{2})$<br>
     * $c=2\cdot\text{atan2}(\sqrt{a},\sqrt{1-a})$<br>
     * $d=R\cdot c$
     * </p>
     *
     * @param lat1 출발지 위도
     * @param lon1 출발지 경도
     * @param lat2 도착지 위도
     * @param lon2 도착지 경도
     * @return 두 지점 사이의 실제 거리 (미터 단위)
     */
    private static double calculateHaversineDistance(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);

        double rLat1 = Math.toRadians(lat1);
        double rLat2 = Math.toRadians(lat2);

        double sinDLat = Math.sin(dLat / 2);
        double sinDLon = Math.sin(dLon / 2);
        double a = sinDLat * sinDLat +
                Math.cos(rLat1) * Math.cos(rLat2) * sinDLon * sinDLon;

        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));

        return EARTH_RADIUS_METERS * c;
    }
}
