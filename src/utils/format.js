// Время симуляции приходит с бэкенда в UTC (ISO, с суффиксом Z).
// Показываем его по часам предприятия (Новосибирск), чтобы смена начиналась в 08:00.
const PLANT_TZ = 'Asia/Novosibirsk';

export const fmtTime = (iso) =>
  iso
    ? new Date(iso).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit', timeZone: PLANT_TZ })
    : '--:--';

// «5 октября»
export const fmtDate = (iso) =>
  iso ? new Date(iso).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long', timeZone: PLANT_TZ }) : '';

// Время из БД (LocalDateTime без зоны, например "2026-10-05T08:12:00") -> "08:12"
export const fmtDbTime = (localDateTime) => (localDateTime ? String(localDateTime).slice(11, 16) : '--:--');

const cap = (s) => (s ? s.charAt(0).toUpperCase() + s.slice(1) : s);
export const capitalize = cap;
