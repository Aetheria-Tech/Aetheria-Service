package com.serverbe;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/*
 * MySQL·Redis 등 전체 인프라가 필요한 컨텍스트 로딩 테스트.
 * 기본 `gradlew test` 에서는 제외되며 `gradlew integrationTest` 로만 실행됩니다.
 */
@Tag("integration")
@SpringBootTest
class ServerBeApplicationTests {

    @Test
    void contextLoads() {
    }

}
