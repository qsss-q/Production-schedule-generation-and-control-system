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
export function ovenView(oven) {
  const v = VIEWS[oven?.state] ?? VIEWS.discharging;
  return { ...v, pc: Math.round(oven?.progressPct ?? 0), over: oven?.state === 'overhold' };
}
