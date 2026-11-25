// Слой работы с бэкендом. Все запросы к серверу — только отсюда.
//
// Два вида эндпоинтов:
//  1) /api/v1/...  — живая симуляция. Ответ в конверте { success, data, error }.
//  2) /api/...     — данные из БД (справочник, настройки, журнал действий, температура).
//                    Ответ — обычный JSON.

async function request(url, options = {}) {
  const res = await fetch(url, {
    // Живая телеметрия не должна подставляться из HTTP-кэша браузера.
    cache: 'no-store',
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  const text = await res.text();
  let body = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    // ответ не JSON — оставим body = null
  }
  if (!res.ok) {
    throw new Error(`HTTP ${res.status}: ${body?.error?.message ?? body?.message ?? 'Ошибка запроса'}`);
  }
  return body;
}

// Симулятор: разворачиваем конверт { success, data, error }
async function v1(path, options) {
  const body = await request(`/api/v1${path}`, options);
  if (!body?.success) {
    throw new Error(body?.error?.message ?? 'Ошибка API');
  }
  return body.data;
}

// БД: обычный JSON
const db = (path, options) => request(`/api${path}`, options);

const send = (method, data) => ({
  method,
  body: data === undefined ? undefined : JSON.stringify(data),
});

// ---------- Симулятор (/api/v1) ----------

export const getBattery = () => v1('/battery');
export const getSnapshot = () => v1('/snapshot');
export const getState = () => v1('/state');
export const getOvens = () => v1('/ovens?zoom=battery');
export const getMachines = () => v1('/machines');
export const getAnalytics = (limit = 20) => v1(`/analytics?limit=${limit}`);
export const getFeed = (limit = 20) => v1(`/feed?limit=${limit}`);
export const getEvents = (limit = 200) => v1(`/events?limit=${limit}`);

// action: 'start' | 'pause' | 'step' | 'reset'
export const control = (action, minutes) => v1('/simulator/control', send('POST', { action, minutes }));
// speedFactor, seed, pushJamProb, ovenFaultPerHour, seriality — любые из них;
// серийность и seed пересобирают симулятор
export const configure = (body) => v1('/simulator/config', send('PUT', body));
// { type: 'pushJam' } или { type: 'ovenHeating', ovenNumber: 5 }
export const forceFailure = (body) => v1('/simulator/failure', send('POST', body));
// «Сформировать график»: реальный прогон планировщика, план уходит в симулятор
export const generateSchedule = (limit = 20) => v1(`/schedule/generate?limit=${limit}`, send('POST'));
// Запись в журнал действий оператора (с временем симуляции)
export const logOperatorAction = (summary, action = 'UI_ACTION') =>
  v1('/operator-actions', send('POST', { action, summary }));

// ---------- Прогоны планировщика (/api/v1/schedule-runs) ----------

// { algorithm: 'b2_idle', holdNotifyMin, holdAutoSkipMin } — схема и seed берутся из текущего прогона
export const createRun = (body = {}) => v1('/schedule-runs', send('POST', body));
export const getActiveRun = () => v1('/schedule-runs/active');
export const getRunOperations = (id) => v1(`/schedule-runs/${id}/operations`);
// { algorithmCodes: [...], seriality?, windowMin? } — сравнение на одном входе
export const compareRuns = (body) => v1('/schedule-runs/compare', send('POST', body));
// ручная правка: { plannedStart: ISO, reason } — причина обязательна
export const manualEditOperation = (id, body) => v1(`/schedule-operations/${id}`, send('PATCH', body));

// доступность: печь — вывод из серии (слот пропускается), машина — простой
export const setOvenAvailability = (number, available, reason) =>
  v1(`/ovens/${number}/availability`, send('POST', { available, reason }));
export const machineDown = (code, minutes = 0) => v1(`/machines/${code}/down`, send('POST', { minutes }));
export const machineUp = (code) => v1('/machines/' + code + '/up', send('POST'));

// ---------- Файлы ----------

// Ссылки для скачивания (ответ — сам документ, открывается в браузере)
export const stateExportUrl = '/api/v1/simulator/state/export';
export const runExportCsvUrl = (id, type = 'operations') =>
  `/api/v1/schedule-runs/${id}/export.csv?type=${type}`;

// Импорт справочника из CSV-файла (docs/04 §2–3). Файл читается как текст.
async function importCsv(path, file) {
  const text = await file.text();
  const res = await fetch(`/api/v1${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'text/csv' },
    body: text,
  });
  const body = await res.json();
  if (!body?.success) throw new Error(body?.error?.message ?? 'Импорт не прошёл');
  return body.data;
}
export const importOvensCsv = (file) => importCsv('/import/ovens', file);
export const importMachinesCsv = (file) => importCsv('/import/machines', file);

// Загрузка состояния батареи из файла снимка (JSON, выгруженного через stateExportUrl)
export const importStateFile = async (file) => {
  const text = await file.text();
  try {
    JSON.parse(text);
  } catch {
    throw new Error('файл не читается как JSON');
  }
  const res = await fetch('/api/v1/simulator/state/import', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: text,
  });
  const body = await res.json();
  if (!body?.success) throw new Error(body?.error?.message ?? 'Загрузка состояния не прошла');
  return body.data;
};

// ---------- БД (/api) ----------

export const getEquipment = () => db('/equipment');
export const setEquipmentAvailability = (id, available) =>
  db(`/equipment/${id}/availability?available=${available}`, { method: 'PATCH' });

export const getActiveSettings = () => db('/settings/active');
export const saveSettings = (id, settings) => db(`/settings/${id}`, send('PUT', settings));

export const getAudit = () => db('/audit');
export const getTemperature = (ovenNumber) => db(`/temperature/by-oven-number/${ovenNumber}`);
