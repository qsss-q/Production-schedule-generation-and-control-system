// Как рисовать печь в зависимости от состояния, которое отдаёт бэкенд (поле state).
// Палитра — по правке куратора (#27, 06.10) и docs/02 §3: цветами обозначаются
// срывы графика, а не «внимание»: зелёный — штатное, жёлтый градациями —
// срыв графика (насыщенность = масштаб простоя), оранжевый — печь вне серии,
// красный — авария. Процессы (коксование, выдача) — спокойный синий.
// Классы Tailwind записаны целиком, иначе они не попадут в сборку стилей.
const VIEWS = {
  ready: { color: '#10B981', stat: 'Готова к выгрузке', txt: 'text-[#059669]', border: 'border-[#10B981]' },
  coking: { color: '#3B82F6', stat: 'Коксование', txt: 'text-[#1D4ED8]', border: 'border-[#3B82F6]' },
  fault: { color: '#EF4444', stat: 'Авария обогрева', txt: 'text-[#DC2626]', border: 'border-[#EF4444]' },
  unavailable: { color: '#F97316', stat: 'Вне серии (ремонт)', txt: 'text-[#C2410C]', border: 'border-[#F97316]' },
  discharging: { color: '#6366F1', stat: 'Выдача / загрузка', txt: 'text-[#4338CA]', border: 'border-[#6366F1]' },
};

// Для легенды над батареей: порядок и подписи. Передержка — жёлтый градаций.
export const LEGEND = [
  { state: 'discharging', label: 'Выдача / загрузка' },
  { state: 'coking', label: 'Коксование' },
  { state: 'ready', label: 'Готова' },
  { state: 'overhold', label: 'Срыв: передержка' },
  { state: 'fault', label: 'Авария' },
  { state: 'unavailable', label: 'Вне серии' },
];

// Шкала жёлтого для срыва графика: чем дольше перестой, тем насыщеннее (docs/02 §3).
// Пороги — в допусках передержки: 1× = светлый, 5× = средний, 15× = насыщенный.
export function overholdShade(deviationMin, toleranceMin = 5) {
  const dev = Number(deviationMin) || 0;
  if (dev <= 5 * toleranceMin) return '#FDE047';        // жёлтый-300: небольшой срыв
  if (dev <= 15 * toleranceMin) return '#FACC15';       // жёлтый-400: заметный
  return '#EAB308';                                     // жёлтый-500: долгий перестой
}

export const legendColor = (state) =>
  state === 'overhold' ? overholdShade(5 * 5) : (VIEWS[state]?.color ?? VIEWS.discharging.color);

// oven — объект из GET /api/v1/ovens
export function ovenView(oven, state, battery) {
  const tolerance = Number(battery?.toleranceMin) || 5;
  const base = oven?.state === 'overhold'
    ? {
        color: overholdShade(oven.deviationMin, tolerance),
        stat: 'Срыв графика: передержка',
        txt: 'text-[#A16207]',
        border: 'border-[#EAB308]',
      }
    : VIEWS[oven?.state] ?? VIEWS.discharging;
  return { ...base, pc: ovenProgress(oven, state, battery), over: oven?.state === 'overhold' };
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
  if (oven?.state === 'discharging' || oven?.state === 'fault' || oven?.state === 'unavailable') return 0;

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
