package org.example.coke_factory.api;

import org.example.coke_factory.entity.CokeOvens;
import org.example.coke_factory.entity.Equipment;
import org.example.coke_factory.entity.EquipmentType;
import org.example.coke_factory.entity.OvenState;
import org.example.coke_factory.repository.CokeOvenRepository;
import org.example.coke_factory.repository.EquipmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Интеграционные проверки API + Spring Data JPA + настоящий PostgreSQL.
 * Симулятор не подменяется и его внутреннее состояние этими тестами не проверяется.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class   PostgresApiIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("coke_factory_test")
            .withUsername("test_user")
            .withPassword("test_password");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.show-sql", () -> "false");
        registry.add("spring.sql.init.mode", () -> "always");
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired EquipmentRepository equipmentRepository;
    @Autowired CokeOvenRepository ovenRepository;

    @Test
    void postgresIsConnectedAndDemoSchemaIsInitialized() {
        Integer equipmentCount = jdbc.queryForObject("select count(*) from equipment", Integer.class);
        Integer ovenCount = jdbc.queryForObject("select count(*) from coke_ovens", Integer.class);

        assertThat(equipmentCount).isNotNull().isGreaterThanOrEqualTo(73);
        assertThat(ovenCount).isNotNull().isGreaterThanOrEqualTo(69);
        assertThat(jdbc.queryForObject("select 1", Integer.class)).isEqualTo(1);
    }

    @Test
    void equipmentCrudThroughRestApiIsPersistedInPostgres() {
        String name = "IT equipment " + UUID.randomUUID();
        Map<String, Object> createBody = Map.of(
                "name", name,
                "type", "PUSHER",
                "available", true
        );

        ResponseEntity<Map> created = http.postForEntity("/api/equipment", createBody, Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(created.getBody()).isNotNull();
        Long id = ((Number) created.getBody().get("id")).longValue();

        Integer persisted = jdbc.queryForObject(
                "select count(*) from equipment where id = ? and name = ? and type = 'PUSHER'",
                Integer.class, id, name);
        assertThat(persisted).isEqualTo(1);

        Map<String, Object> updateBody = Map.of(
                "name", name + " updated",
                "type", "PUSHER",
                "available", false
        );
        ResponseEntity<Map> updated = http.exchange(
                "/api/equipment/" + id, HttpMethod.PUT,
                new HttpEntity<>(updateBody), Map.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<?, ?> readBack = http.getForObject("/api/equipment/" + id, Map.class);
        assertThat(readBack).isNotNull();
        assertThat(readBack.get("name")).isEqualTo(name + " updated");
        assertThat(readBack.get("available")).isEqualTo(false);

        http.delete("/api/equipment/" + id);
        assertThat(jdbc.queryForObject(
                "select count(*) from equipment where id = ?", Integer.class, id)).isZero();
    }

    @Test
    void ovenEquipmentRelationIsStoredAndCanBeReadBack() {
        Equipment equipment = new Equipment();
        equipment.setName("IT oven equipment " + UUID.randomUUID());
        equipment.setType(EquipmentType.OVEN);
        equipment.setAvailable(true);
        Equipment savedEquipment = equipmentRepository.saveAndFlush(equipment);

        CokeOvens oven = new CokeOvens();
        oven.setNameOven("IT oven " + UUID.randomUUID());
        oven.setBatteryNumber(99);
        oven.setState(OvenState.COKING);
        oven.setProgressPercent(42.0);
        oven.setEquipment(savedEquipment);
        CokeOvens savedOven = ovenRepository.saveAndFlush(oven);

        Map<String, Object> relation = jdbc.queryForMap(
                "select o.name_oven, e.name as equipment_name, o.equipment_id " +
                        "from coke_ovens o join equipment e on e.id = o.equipment_id where o.id = ?",
                savedOven.getId());

        assertThat(relation.get("name_oven")).isEqualTo(oven.getNameOven());
        assertThat(relation.get("equipment_name")).isEqualTo(equipment.getName());
        assertThat(((Number) relation.get("equipment_id")).longValue()).isEqualTo(savedEquipment.getId());
        assertThat(ovenRepository.findByNameOven(oven.getNameOven())).isPresent();
    }
}
