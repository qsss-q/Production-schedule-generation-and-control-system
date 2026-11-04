// Слой работы с бэкендом. Все запросы к серверу — только отсюда.
//
// Два вида эндпоинтов:
//  1) /api/v1/...  — живая симуляция. Ответ в конверте { success, data, error }.
//  2) /api/...     — данные из БД (справочник, настройки, журнал действий, температура).
//                    Ответ — обычный JSON.

async function request(url, options = {}) {
  const res = await fetch(url, {
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
    throw new Error(body?.error?.message ?? body?.message ?? `HTTP ${res.status}`);
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
export const getState = () => v1('/state');
export const getOvens = () => v1('/ovens?zoom=battery');
export const getMachines = () => v1('/machines');
export const getFeed = (limit = 20) => v1(`/feed?limit=${limit}`);
export const getEvents = (limit = 200) => v1(`/events?limit=${limit}`);

// action: 'start' | 'pause' | 'step' | 'reset'
export const control = (action, minutes) => v1('/simulator/control', send('POST', { action, minutes }));
// speedFactor, seed, pushJamProb, ovenFaultPerHour — любые из них
export const configure = (body) => v1('/simulator/config', send('PUT', body));
// { type: 'pushJam' } или { type: 'ovenHeating', ovenNumber: 5 }
export const forceFailure = (body) => v1('/simulator/failure', send('POST', body));
// «Сформировать график»: сохраняет плановые операции в БД и возвращает их
export const generateSchedule = (limit = 20) => v1(`/schedule/generate?limit=${limit}`, send('POST'));
// Запись в журнал действий оператора (с временем симуляции)
export const logOperatorAction = (summary, action = 'UI_ACTION') =>
  v1('/operator-actions', send('POST', { action, summary }));

// ---------- БД (/api) ----------

export const getEquipment = () => db('/equipment');
export const setEquipmentAvailability = (id, available) =>
  db(`/equipment/${id}/availability?available=${available}`, { method: 'PATCH' });

export const getActiveSettings = () => db('/settings/active');
export const saveSettings = (id, settings) => db(`/settings/${id}`, send('PUT', settings));

export const getAudit = () => db('/audit');
export const getTemperature = (ovenNumber) => db(`/temperature/by-oven-number/${ovenNumber}`);
