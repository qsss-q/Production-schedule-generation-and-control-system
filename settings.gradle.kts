rootProject.name = "coke_factory"

// Симулятор коксовой батареи (код Вани) подключён как отдельная сборка.
// Лежит в папке simulator/, подтягивается как обычная зависимость
// "ru.evraz.koks:koks-simulator" — см. build.gradle.kts.
includeBuild("simulator")
