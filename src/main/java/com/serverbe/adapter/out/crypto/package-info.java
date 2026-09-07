/**
 * <h2>Outgoing Crypto Adapters</h2>
 * <p>
 * {@code application.port.out.crypto}의 구현체가 위치합니다. 애플리케이션은
 * {@code EncryptPort.encrypt}를 부를 뿐 그것이 AES-GCM인지 KMS인지 알지 못하며,
 * <b>포트를 구현하는 것은 방향상 아웃바운드 어댑터</b>이므로 여기가 자리입니다.
 * </p>
 * <p>
 * 암호화 컨텍스트 전파({@code infrastructure.crypto.EncryptionContext})는 <b>여기 없습니다.</b>
 * 그것은 포트 구현이 아니라 JPA 컨버터와 인터셉터가 공유하는 스프링 배선이라 인프라에 남았습니다.
 * </p>
 */
package com.serverbe.adapter.out.crypto;
