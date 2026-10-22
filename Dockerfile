FROM maven:3.9.6-amazoncorretto-21 AS build
WORKDIR /app

# Копируем файлы сборщика и исходный код
COPY pom.xml .
COPY src ./src

# Собираем проект (пропуская тесты для экономии времени)
RUN mvn clean package -DskipTests

# Этап 2: Запуск готового Java-приложения
FROM amazoncorretto:21-alpine
WORKDIR /app

# Забираем собранный jar-файл из первого этапа
COPY --from=build /app/target/*.jar app.jar

# Команда для запуска приложения
ENTRYPOINT ["java", "-jar", "app.jar"]
