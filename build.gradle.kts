plugins {
    java
    id("org.springframework.boot") version "3.4.4"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "org.example"
version = "0.0.1-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

// Testcontainers 1.20.x (версия из BOM Spring Boot 3.4.4) обращается к Docker по API 1.32,
// а Docker Engine 29+ / свежий Docker Desktop принимают только API >= 1.40/1.44:
// PostgresApiIntegrationTest падал с "Could not find a valid Docker environment".
// В 1.21.4 клиент договаривается о версии API с демоном.
extra["testcontainers.version"] = "1.21.4"

dependencies {
    // Веб (REST) и работа с БД
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")

    // Swagger UI: http://localhost:8080/swagger-ui.html
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.9")

    // Симулятор батареи (папка simulator/, composite build)
    implementation("ru.evraz.koks:koks-simulator:0.1.0")

    // Lombok (@Getter, @Setter, @RequiredArgsConstructor ...)
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Реальный PostgreSQL в интеграционных тестах (не H2 и не моки)
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// Один исполняемый jar с понятным именем (его забирает Dockerfile)
tasks.jar { enabled = false }
tasks.bootJar { archiveFileName.set("app.jar") }
