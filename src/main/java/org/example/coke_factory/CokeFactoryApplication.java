package org.example.coke_factory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling   // нужен для тика симулятора раз в секунду
public class CokeFactoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(CokeFactoryApplication.class, args);
    }
}
