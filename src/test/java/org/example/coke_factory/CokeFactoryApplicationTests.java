package org.example.coke_factory;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Disabled("Нужна запущенная БД: включайте при поднятом docker compose (coke-postgres-db)")
class CokeFactoryApplicationTests {

    @Test
    void contextLoads() {
    }

}
