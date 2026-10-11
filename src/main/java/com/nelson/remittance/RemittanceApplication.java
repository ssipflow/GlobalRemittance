package com.nelson.remittance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
// 송금 서비스의 시작점이며 패키지 아래 어댑터와 구성 객체를 함께 조립한다.
public class RemittanceApplication {
    // 실행 인자를 전달해 Spring 컨텍스트와 HTTP 서버를 시작한다.
    public static void main(String[] args) {
        SpringApplication.run(RemittanceApplication.class, args);
    }
}
