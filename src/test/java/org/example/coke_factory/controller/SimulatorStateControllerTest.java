package org.example.coke_factory.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.coke_factory.exception.ApiExceptionHandler;
import org.example.coke_factory.service.AuditService;
import org.example.coke_factory.service.ScheduleService;
import org.example.coke_factory.service.SimulatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import ru.evraz.koks.sim.Simulator;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Выгрузка и загрузка состояния батареи через HTTP: реальный контроллер, сервис и
 * JSON, без Spring-контекста и базы. Журнал подменён — проверяем только сам факт записи.
 */
class SimulatorStateControllerTest {

    private static final String EXPORT = "/api/v1/simulator/state/export";
    private static final String IMPORT = "/api/v1/simulator/state/import";

    private final ObjectMapper json = new ObjectMapper();
    private SimulatorService simulator;
    private AuditService audit;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        simulator = new SimulatorService();
        audit = mock(AuditService.class);
        mvc = standaloneSetup(new SimulatorController(simulator, mock(ScheduleService.class), audit, json))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void export_returnsDownloadableJsonDocumentOfWholeBattery() throws Exception {
        simulator.control(new SimulatorService.ControlRequest("step", 120L));

        var response = mvc.perform(get(EXPORT)).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .startsWith("attachment")
                .contains("battery-state-120min.json");

        Simulator.BatteryState state = json.readValue(body(response), Simulator.BatteryState.class);
        assertThat(state.formatVersion()).isEqualTo(2);
        assertThat(state.simMin()).isEqualTo(120);
        assertThat(state.config().ovenCount()).isEqualTo(69);
        assertThat(state.ovens()).hasSize(69);
        assertThat(state.machines()).hasSize(4);
        assertThat(state.discharges()).isGreaterThan(0);
    }

    @Test
    void import_acceptsExportedDocumentAndPausesAtItsTime() throws Exception {
        simulator.control(new SimulatorService.ControlRequest("step", 120L));
        byte[] file = mvc.perform(get(EXPORT)).andReturn().getResponse().getContentAsByteArray();

        simulator.control(new SimulatorService.ControlRequest("step", 300L));
        var response = mvc.perform(post(IMPORT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(file))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        JsonNode envelope = json.readTree(body(response));

        assertThat(envelope.get("success").asBoolean()).isTrue();
        assertThat(envelope.get("data").get("simMinutes").asLong()).isEqualTo(120);
        assertThat(envelope.get("data").get("running").asBoolean()).isFalse();
        assertThat(simulator.state().simMinutes()).isEqualTo(120);
        // в записи журнала обе метки времени: откуда ушли и что загружено (T0 + 120 мин)
        verify(audit).log(eq("SIMULATOR_IMPORT_STATE"), eq("Simulator"), isNull(),
                contains("2026-10-05T03:00:00Z"));
    }

    @Test
    void import_rejectsBrokenFiles_andKeepsCurrentRun() throws Exception {
        simulator.control(new SimulatorService.ControlRequest("step", 120L));

        // Посторонний документ. Какой именно код ошибки — зависит от настройки JSON-маппера
        // (Spring Boot игнорирует неизвестные поля), поэтому проверяем только отказ.
        var foreign = mvc.perform(post(IMPORT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hello\": \"world\"}"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse();
        assertThat(json.readTree(body(foreign)).get("success").asBoolean()).isFalse();

        // Снимок без печей — структурно корректный JSON, но с ним нельзя продолжать
        Simulator.BatteryState exported = simulator.exportState();
        ObjectNode withoutOvens = json.valueToTree(exported);
        withoutOvens.putArray("ovens");
        var incomplete = mvc.perform(post(IMPORT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withoutOvens.toString()))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse();
        JsonNode envelope = json.readTree(body(incomplete));
        assertThat(envelope.get("success").asBoolean()).isFalse();
        assertThat(envelope.get("error").get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(envelope.get("error").get("message").asText()).contains("печей");

        var notJson = mvc.perform(post(IMPORT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ это не json"))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse();
        assertThat(json.readTree(body(notJson)).get("error").get("code").asText())
                .isEqualTo("UNREADABLE_BODY");

        assertThat(simulator.state().simMinutes()).isEqualTo(120);
        assertThat(simulator.state().counters().discharges()).isEqualTo(exported.discharges());
        verify(audit, never()).log(any(), any(), any(), any());
    }

    private static String body(MockHttpServletResponse response) throws UnsupportedEncodingException {
        return response.getContentAsString(StandardCharsets.UTF_8);
    }
}
