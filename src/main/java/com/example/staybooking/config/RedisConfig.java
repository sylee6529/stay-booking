package com.example.staybooking.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
public class RedisConfig {

    @Bean
    public RedisScript<Long> admissionScript() {
        return RedisScript.of(new ClassPathResource("redis/admission.lua"), Long.class);
    }

    @Bean
    public RedisScript<Long> rateLimitScript() {
        return RedisScript.of(new ClassPathResource("redis/rate_limit.lua"), Long.class);
    }
}
