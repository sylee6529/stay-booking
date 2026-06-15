package com.example.staybooking.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
public class RedisConfig {

    /** 재고 admission Lua. 결과는 Long(정수)로 받는다 (docs/04). */
    @Bean
    public RedisScript<Long> admissionScript() {
        return RedisScript.of(new ClassPathResource("redis/admission.lua"), Long.class);
    }
}
