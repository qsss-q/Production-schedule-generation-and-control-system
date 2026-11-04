# Этап 1: сборка проекта (Gradle wrapper, JDK 21)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Копируем проект целиком (вместе с папкой simulator/)
COPY . .

# На случай, если gradlew скопирован с windows-переводами строк
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew

# Собираем исполняемый jar, пропуская тесты
RUN ./gradlew bootJar --no-daemon -x test

# Этап 2: запуск готового приложения
FROM amazoncorretto:21-alpine
WORKDIR /app

COPY --from=build /app/build/libs/app.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
