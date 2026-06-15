package com.example.staybooking.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

/**
 * 통합 테스트 베이스. MySQL 8 컨테이너를 JVM당 1회만 기동해 전 테스트가 공유한다.
 *
 * <p>H2 호환 모드로는 조건부 UPDATE 원자성·UNIQUE·CHECK·JSON 컬럼을 검증할 수 없으므로
 * 실제 MySQL을 띄운다. 컨테이너는 static 싱글턴으로 한 번 start 하고 JVM 종료 시 Ryuk가 정리한다.
 */
@SpringBootTest
public abstract class IntegrationTestSupport {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("staybooking")
            .withUsername("app")
            .withPassword("app1234");

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static {
        MYSQL.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void containerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
