/**
 * @responsibility 애플리케이션 계층이 필요로 하는 <b>설정값</b>을 프레임워크와 무관한 형태로 정의합니다.
 * @implSpec 이 패키지의 타입은 애노테이션도, {@code com.serverbe} 바깥 import도 갖지 않습니다.
 * 값을 채워 넣는 일은 {@code infrastructure.config.ApplicationPolicyConfig}가 합니다.
 * @implNote 이전에는 서비스들이 {@code infrastructure.config.properties.*} 레코드를 직접 import했습니다.
 * 애플리케이션이 인프라를 아는 역방향 의존이었고, 테스트에서도 쓰지 않는 필드까지 채운 프로퍼티
 * 객체를 만들어야 했습니다.
 */
package com.serverbe.application.config;
