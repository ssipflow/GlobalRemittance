package com.nelson.remittance.bootstrap;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfiguration {
    @Bean
    // 업무 시간은 하나의 UTC Clock으로 공급하고 테스트에서는 고정 시간으로 바꾼다.
    Clock clock() {
        return Clock.systemUTC();
    }
}
