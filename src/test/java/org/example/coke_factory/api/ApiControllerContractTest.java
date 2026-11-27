package org.example.coke_factory.api;

import org.example.coke_factory.controller.*;
import org.example.coke_factory.entity.*;
import org.example.coke_factory.exception.ApiExceptionHandler;
import org.example.coke_factory.repository.*;
import org.example.coke_factory.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * HTTP-контракт REST-контроллеров: маршруты, параметры, статусы, сериализация,
 * валидация входа и вызовы сервисов/репозиториев. БД и алгоритмы симулятора здесь
 * намеренно заменены моками — это быстрые controller-slice тесты.
 */
class ApiControllerContractTest {
    private MockMvc mvc;
    private EquipmentRepository equipment;
    private CokeOvenRepository ovens;
    private ScheduleOperationRepository operations;
    private ScheduleSettingsRepository settings;
    private NotificationRepository notifications;
    private AuditLogRepository auditLogs;
    private OvenTemperatureReadingRepository temperatures;
    private SimulatorService simulator;
    private ScheduleRunService runs;
    private DirectoryCsvService csv;
    private AuditService audit;

    @BeforeEach
    void setUp() {
        equipment = mock(EquipmentRepository.class);
        ovens = mock(CokeOvenRepository.class);
        operations = mock(ScheduleOperationRepository.class);
        settings = mock(ScheduleSettingsRepository.class);
        notifications = mock(NotificationRepository.class);
        auditLogs = mock(AuditLogRepository.class);
        temperatures = mock(OvenTemperatureReadingRepository.class);
        simulator = mock(SimulatorService.class);
        runs = mock(ScheduleRunService.class);
        csv = mock(DirectoryCsvService.class);
        audit = mock(AuditService.class);

        when(equipment.findAll()).thenReturn(List.of());
        when(ovens.findAll()).thenReturn(List.of());
        when(operations.findAll()).thenReturn(List.of());
        when(settings.findAll()).thenReturn(List.of());
        when(notifications.findByIsReadFalseOrderByCreatedAtDesc()).thenReturn(List.of());
        when(auditLogs.findAllByOrderByCreatedAtDesc()).thenReturn(List.of());
        when(temperatures.findByOvenIdOrderByRecordedAtAsc(anyLong())).thenReturn(List.of());
        when(temperatures.findByOvenName(anyString())).thenReturn(List.of());
        when(runs.list()).thenReturn(List.of());
        when(runs.activeRun()).thenReturn(Optional.empty());

        mvc = standaloneSetup(
                new EquipmentController(equipment, simulator, audit),
                new CokeOvenController(ovens, audit),
                new ScheduleOperationController(operations, audit),
                new ScheduleSettingsController(settings, audit),
                new NotificationController(notifications),
                new AuditLogController(auditLogs),
                new TemperatureController(temperatures),
                new ScheduleRunController(runs, simulator, csv)
        ).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void equipmentEndpoints_supportListFilterAndNotFound() throws Exception {
        mvc.perform(get("/api/equipment")).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/equipment").param("type", "PUSHER"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/equipment/404")).andExpect(status().isNotFound());
        verify(equipment).findByType(EquipmentType.PUSHER);
    }


    @Test
    void equipmentDirectoryUsesLiveSimulatorAvailabilityInsteadOfStaleDatabaseFlag() throws Exception {
        Equipment pusher = new Equipment();
        pusher.setId(10L);
        pusher.setName("Выталкиватель КВ-1");
        pusher.setType(EquipmentType.PUSHER);
        pusher.setAvailable(false); // устаревшее значение, сохранённое в БД
        when(equipment.findAll()).thenReturn(List.of(pusher));
        when(simulator.machineDown(ru.evraz.koks.sim.MachineKind.PUSHING)).thenReturn(false);

        mvc.perform(get("/api/equipment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].available").value(true));
        verify(simulator).machineDown(ru.evraz.koks.sim.MachineKind.PUSHING);
    }

    @Test
    void equipmentDirectoryShowsMachineUnavailableWhenLiveSimulatorIsDown() throws Exception {
        Equipment pusher = new Equipment();
        pusher.setId(10L);
        pusher.setName("Выталкиватель КВ-1");
        pusher.setType(EquipmentType.PUSHER);
        pusher.setAvailable(true);
        when(equipment.findAll()).thenReturn(List.of(pusher));
        when(simulator.machineDown(ru.evraz.koks.sim.MachineKind.PUSHING)).thenReturn(true);

        mvc.perform(get("/api/equipment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].available").value(false));
    }

    @Test
    void equipmentAvailabilityPatchTakesMachineDownAndReturnsLiveState() throws Exception {
        Equipment pusher = new Equipment();
        pusher.setId(10L);
        pusher.setName("Выталкиватель КВ-1");
        pusher.setType(EquipmentType.PUSHER);
        pusher.setAvailable(true);
        when(equipment.findById(10L)).thenReturn(Optional.of(pusher));
        when(equipment.save(any(Equipment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(simulator.machineDown(ru.evraz.koks.sim.MachineKind.PUSHING)).thenReturn(true);

        mvc.perform(patch("/api/equipment/10/availability").param("available", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false));

        verify(simulator).setMachineDown(ru.evraz.koks.sim.MachineKind.PUSHING, 0);
        verify(equipment).save(argThat(saved -> Boolean.FALSE.equals(saved.getAvailable())));
    }

    @Test
    void equipmentAvailabilityPatchRestoresMachine() throws Exception {
        Equipment pusher = new Equipment();
        pusher.setId(10L);
        pusher.setName("Выталкиватель КВ-1");
        pusher.setType(EquipmentType.PUSHER);
        pusher.setAvailable(false);
        when(equipment.findById(10L)).thenReturn(Optional.of(pusher));
        when(equipment.save(any(Equipment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(simulator.machineDown(ru.evraz.koks.sim.MachineKind.PUSHING)).thenReturn(false);

        mvc.perform(patch("/api/equipment/10/availability").param("available", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true));

        verify(simulator).setMachineUp(ru.evraz.koks.sim.MachineKind.PUSHING);
    }

    @Test
    void ovenEndpoints_returnListAndNotFound() throws Exception {
        mvc.perform(get("/api/ovens")).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/ovens/404")).andExpect(status().isNotFound());
    }

    @Test
    void operationQueries_supportFiltersRangesAndMissingRecords() throws Exception {
        mvc.perform(get("/api/operations").param("manual", "true"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/operations/oven/7")).andExpect(status().isOk());
        mvc.perform(get("/api/operations/status/PLANNED")).andExpect(status().isOk());
        mvc.perform(get("/api/operations/range").param("from", "2026-10-05T08:00:00")
                .param("to", "2026-10-05T16:00:00")).andExpect(status().isOk());
        mvc.perform(get("/api/operations/404")).andExpect(status().isNotFound());
        mvc.perform(get("/api/operations/upcoming")).andExpect(status().isOk());
    }

    @Test
    void settingsEndpoints_reportNoActiveProfileAndList() throws Exception {
        mvc.perform(get("/api/settings")).andExpect(status().isOk()).andExpect(content().json("[]"));
        when(settings.findFirstByActiveTrue()).thenReturn(Optional.empty());
        mvc.perform(get("/api/settings/active")).andExpect(status().isNotFound());
    }

    @Test
    void notificationEndpoints_exposeUnreadAndReadNotFound() throws Exception {
        mvc.perform(get("/api/notifications/unread")).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/notifications")).andExpect(status().isOk());
        when(notifications.findById(404L)).thenReturn(Optional.empty());
        mvc.perform(patch("/api/notifications/404/read")).andExpect(status().isNotFound());
        when(notifications.existsById(404L)).thenReturn(false);
        mvc.perform(delete("/api/notifications/404")).andExpect(status().isNotFound());
    }

    @Test
    void auditEndpoints_supportGlobalAndEntityQueries() throws Exception {
        mvc.perform(get("/api/audit")).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/audit/entity").param("name", "Equipment").param("id", "1"))
                .andExpect(status().isOk());
        verify(auditLogs).findByEntityNameAndEntityIdOrderByCreatedAtDesc("Equipment", 1L);
    }

    @Test
    void temperatureEndpoints_supportOvenIdAndOvenNumber() throws Exception {
        mvc.perform(get("/api/temperature/oven/1")).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/temperature/by-oven-number/12"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verify(temperatures).findByOvenName("Печь П-12");
    }

    @Test
    void scheduleRunEndpoints_exposeListAndEmptyActiveRun() throws Exception {
        mvc.perform(get("/api/v1/schedule-runs")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/schedule-runs/active")).andExpect(status().isOk());
        verify(runs).list();
        verify(runs).activeRun();
    }

    @Test
    void machineAvailabilityEndpoint_rejectsUnknownMachineCode() throws Exception {
        mvc.perform(post("/api/v1/machines/UNKNOWN/up"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        verify(simulator, never()).setMachineUp(any());
    }

    @Test
    void csvImportDelegatesDocumentToDirectoryService() throws Exception {
        mvc.perform(post("/api/v1/import/ovens").contentType("text/csv")
                        .content("oven_number;status\n1;ready"))
                .andExpect(status().isOk());
        verify(csv).importOvens("oven_number;status\n1;ready");
    }

    @Test
    void operationPriorityValidationReturnsBadRequestForOutOfRangeValue() throws Exception {
        mvc.perform(patch("/api/operations/1/priority").param("priority", "4"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        verify(operations, never()).save(any());
    }

    @Test
    void settingsValidationRejectsNonPositiveStep() throws Exception {
        mvc.perform(post("/api/settings").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"bad\",\"stepMinutes\":0,\"defaultPriority\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        verify(settings, never()).save(any());
    }

    @Test
    void equipmentAvailabilityMissingIdReturnsNotFoundWithoutSaving() throws Exception {
        when(equipment.findById(999L)).thenReturn(Optional.empty());
        mvc.perform(patch("/api/equipment/999/availability").param("available", "false"))
                .andExpect(status().isNotFound());
        verify(equipment, never()).save(any());
    }

    @Test
    void scheduleRunCsvMetricsExportUsesCsvContentTypeAndAttachment() throws Exception {
        when(runs.exportMetricsCsv("12345678-aaaa")).thenReturn("metric,value\nM1,0.1");
        mvc.perform(get("/api/v1/schedule-runs/12345678-aaaa/export.csv").param("type", "metrics"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("run-12345678-metrics.csv")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("M1,0.1")));
        verify(runs).exportMetricsCsv("12345678-aaaa");
    }

    @Test
    void scheduleRunDetailAndOperationRoutesDelegateToService() throws Exception {
        mvc.perform(get("/api/v1/schedule-runs/run-123")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/schedule-runs/run-123/operations")).andExpect(status().isOk());
        verify(runs).get("run-123");
        verify(runs).runOperations("run-123");
    }

    @Test
    void scheduleRunCreateCompareAndManualEditAcceptTheirHttpContracts() throws Exception {
        mvc.perform(post("/api/v1/schedule-runs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"algorithm\":\"b1_fifo\",\"seriality\":\"2-1\",\"seed\":42,\"horizon\":100}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/schedule-runs/compare").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"algorithmCodes\":[\"b1_fifo\",\"b2_idle\"],\"seriality\":\"2-1\",\"windowMin\":1200}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/v1/schedule-operations/5").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"plannedStart\":\"2026-10-05T12:00:00\",\"reason\":\"operator move\",\"priority\":2}"))
                .andExpect(status().isOk());
        verify(runs).createRun(any(ScheduleRunService.CreateRunRequest.class), anyString());
        verify(runs).compare(any(ScheduleRunService.CompareRequest.class));
        verify(runs).manualEdit(eq(5L), any(ScheduleRunService.ManualEditRequest.class));
    }

    @Test
    void machineAndOvenAvailabilityRoutesValidateAndDelegate() throws Exception {
        when(simulator.revision()).thenReturn(9L);
        mvc.perform(post("/api/v1/ovens/12/availability").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"available\":false,\"reason\":\"maintenance\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.available").value(false));
        mvc.perform(post("/api/v1/machines/КВ/down").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"minutes\":15}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/machines/КВ/up")).andExpect(status().isOk());
        verify(simulator).setOvenAvailable(12, false, "maintenance");
        verify(simulator).setMachineDown(any(), eq(15.0));
        verify(simulator).setMachineUp(any());
    }

    @Test
    void machineCsvImportDelegatesAndOperationsCsvExportUsesDefaultType() throws Exception {
        mvc.perform(post("/api/v1/import/machines").contentType("text/csv").content("battery;code;type;status"))
                .andExpect(status().isOk());
        when(runs.exportOperationsCsv("run-12345678")).thenReturn("id,status\n1,PLANNED");
        mvc.perform(get("/api/v1/schedule-runs/run-12345678/export.csv"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("1,PLANNED")));
        verify(csv).importMachines("battery;code;type;status");
        verify(runs).exportOperationsCsv("run-12345678");
    }

    @Test
    void equipmentCrudCreatesUpdatesAvailabilityAndDeletes() throws Exception {
        when(equipment.save(any(Equipment.class))).thenAnswer(invocation -> {
            Equipment value = invocation.getArgument(0);
            value.setId(7L);
            return value;
        });
        mvc.perform(post("/api/equipment").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Пушер тестовый\",\"type\":\"PUSHER\",\"available\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(7));

        when(equipment.existsById(7L)).thenReturn(true);
        mvc.perform(put("/api/equipment/7").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Пушер обновлённый\",\"type\":\"PUSHER\",\"available\":true}"))
                .andExpect(status().isOk());

        Equipment current = new Equipment();
        current.setId(7L); current.setName("Пушер обновлённый");
        current.setType(EquipmentType.PUSHER); current.setAvailable(true);
        when(equipment.findById(7L)).thenReturn(Optional.of(current));
        mvc.perform(patch("/api/equipment/7/availability").param("available", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(false));
        mvc.perform(delete("/api/equipment/7")).andExpect(status().isNoContent());
        verify(equipment, atLeast(2)).save(any(Equipment.class));
        verify(simulator).setMachineDown(any(), eq(0.0));
        verify(audit, atLeast(3)).log(anyString(), anyString(), any(), anyString());
    }

    @Test
    void operationCreateRejectsInvalidPriorityBeforePersistence() throws Exception {
        mvc.perform(post("/api/operations").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"priority\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
        verify(operations, never()).save(any());
    }

    @Test
    void temperatureCreatePersistsIncomingReading() throws Exception {
        when(temperatures.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        mvc.perform(post("/api/temperature").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oven\":{\"id\":1},\"recordedAt\":\"2026-10-05T12:00:00\",\"temperature\":1020.5}"))
                .andExpect(status().isOk());
        verify(temperatures).save(any());
    }

    @Test
    void invalidJsonForVersionedApiUsesStableErrorEnvelope() throws Exception {
        mvc.perform(post("/api/v1/schedule-runs").contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNREADABLE_BODY"));
    }
}
