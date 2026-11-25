// Базовый симулятор коксовой батареи. Первый прототип: без веба, без БД,
// всё в памяти. Движок — чистая Java без фреймворков (см. docs/07 §5).
// Плагин java-library нужен, чтобы Spring-бэкенд подключал модуль как
// зависимость (composite build), не тащя в модель фреймворки.
plugins {
    `java-library`
    application // для запуска `./gradlew run`
}

group = "ru.evraz.koks"
version = "0.1.0"

repositories { mavenCentral() }

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "ru.evraz.koks.sim.DemoMain"
}

tasks.test {
    useJUnitPlatform()
    testLogging { showStandardStreams = false }
    // ручной эксперимент слайдов (ExperimentRunner): запускается с -Pexperiment=1
    // или -Dexperiment=1 (обе формы доходят до тестовой JVM как системное свойство)
    val experimentFlag = project.findProperty("experiment")?.toString()
        ?: System.getProperty("experiment")
    if (experimentFlag != null) {
        systemProperty("experiment", experimentFlag)
    }
}
