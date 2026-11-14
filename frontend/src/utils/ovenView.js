// Как рисовать печь в зависимости от состояния, которое отдаёт бэкенд (поле state).
// Цвета — по контракту (docs/08): готова — зелёный, коксование — жёлтый,
// передержка — оранжевый, авария — красный, выдача/загрузка — синий.
// Классы Tailwind записаны целиком, иначе они не попадут в сборку стилей.
const VIEWS = {
  ready: { color: '#10B981', stat: 'Готова к выгрузке', txt: 'text-[#10B981]', border: 'border-[#10B981]' },
  coking: { color: '#EAB308', stat: 'Коксование', txt: 'text-[#A16207]', border: 'border-[#EAB308]' },
  overhold: { color: '#F97316', stat: 'Передержка кокса', txt: 'text-[#C2410C]', border: 'border-[#F97316]' },
  fault: { color: '#EF4444', stat: 'Авария', txt: 'text-[#EF4444]', border: 'border-[#EF4444]' },
  discharging: { color: '#3B82F6', stat: 'Выдача / загрузка', txt: 'text-[#1D4ED8]', border: 'border-[#3B82F6]' },
};

// Для легенды над батареей: порядок и подписи
export const LEGEND = [
  { state: 'discharging', label: 'Выдача / загрузка' },
  { state: 'ready', label: 'Готова' },
  { state: 'coking', label: 'Коксование' },
  { state: 'overhold', label: 'Передержка' },
  { state: 'fault', label: 'Авария' },
];

export const legendColor = (state) => VIEWS[state].color;

// oven — объект из GET /api/v1/ovens
export function ovenView(oven, state, battery) {
  const v = VIEWS[oven?.state] ?? VIEWS.discharging;
  return { ...v, pc: ovenProgress(oven, state, battery), over: oven?.state === 'overhold' };
}

// Один источник отображения прогресса для мини-печей и больших карточек.
// В новом API известны начало коксования и готовность печи: считаем процент
// точно как симулятор. Для старого API используем T_кокс из /battery.
// simTime берём из /state: процент синхронен с таймером, даже если progressPct
// в отдельном GET /ovens устарел из-за кэша браузера.
// Используются исключительно данные симулятора, а не Date.now() браузера.
const clampPercent = (value) => Math.min(100, Math.max(0, value));

export function ovenProgress(oven, state, battery) {
  if (oven?.state === 'ready' || oven?.state === 'overhold') return 100;
  if (oven?.state === 'discharging' || oven?.state === 'fault') return 0;

  if (oven?.state === 'coking') {
    const readyMs = Date.parse(oven.readyAt ?? '');
    const nowMs = Date.parse(state?.simTime ?? '');
    const startedMs = Date.parse(oven.cokingStartedAt ?? '');
    const cokingMin = Number(battery?.cokingMin);
    if (Number.isFinite(readyMs) && Number.isFinite(nowMs)) {
      if (Number.isFinite(startedMs) && readyMs > startedMs) {
        return clampPercent(100 * (nowMs - startedMs) / (readyMs - startedMs));
      }
      // Обратная совместимость: старый бэкенд ещё не отдаёт cokingStartedAt.
      if (Number.isFinite(cokingMin) && cokingMin > 0) {
        return clampPercent(100 * (1 - (readyMs - nowMs) / (cokingMin * 60_000)));
      }
    }
  }

  // Если паспорт батареи ещё загружается, используем исходное значение API.
  const value = Number(oven?.progressPct);
  return Number.isFinite(value) ? clampPercent(value) : 0;
}
