import { capitalize } from './format';

// Событие симулятора -> категория на экране (теги журнала и карточки отклонений):
//   accident  — Авария            (FAULT)
//   schedule  — Срыв графика      (OVERHOLD: печь простояла дольше допуска)
//   deviation — Отклонение        (LATE: выдача позже слота)
//   info      — Информация        (остальное)
export function eventCategory(e) {
  if (e.type === 'FAULT') return 'accident';
  if (e.type === 'OVERHOLD') return 'schedule';
  if (e.type === 'LATE') return 'deviation';
  return 'info';
}

// Заголовок карточки отклонения: «Авария · Выталкиватель», «Срыв графика · П-3»
export function deviationTitle(e) {
  const oven = `П-${e.ovenNumber}`;
  if (e.type === 'FAULT') {
    return e.title.includes('выталкивания') ? 'Авария · Выталкиватель' : `Авария · ${oven}`;
  }
  if (e.type === 'OVERHOLD') return `Срыв графика · ${oven}`;
  if (e.type === 'LATE') return `Отклонение · ${oven}`;
  return `Информация · ${oven}`;
}

export const deviationText = (e) => capitalize(e.title);

// Строка журнала событий
export function journalText(e) {
  const oven = `Печь П-${e.ovenNumber}`;
  switch (e.type) {
    case 'DISCHARGE': return `${oven}: начало выгрузки`;
    case 'LOADED': return `${oven}: загрузка шихты завершена`;
    case 'RECOVERED': return `${oven}: отказ устранён`;
    default: return `${oven}: ${e.title}`;
  }
}

// Цвет точки в журнале (тот же набор, что в дизайне)
export function journalDot(e) {
  switch (e.type) {
    case 'FAULT': return 'bg-[#EF4444]';
    case 'OVERHOLD': return 'bg-[#EAB308]';
    case 'LATE': return 'bg-[#FACC15]';
    case 'LOADED':
    case 'RECOVERED': return 'bg-[#10B981]';
    default: return 'bg-[#3B82F6]';
  }
}

// тип карточки в блоке «Отклонения» (стили DeviationCard)
export const deviationCardType = { accident: 'error', schedule: 'warning', deviation: 'alert', info: 'info' };

// Только события, требующие внимания оператора
export const isDeviation = (e) => eventCategory(e) !== 'info';

// «Новые» отклонения: произошли за последний час симуляции
export const recentDeviations = (events, nowMin, windowMin = 60) =>
  events.filter((e) => isDeviation(e) && e.simMinutes >= nowMin - windowMin);
