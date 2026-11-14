import {
  Bar, BarChart, CartesianGrid, Cell, ReferenceLine, ResponsiveContainer,
  Tooltip, XAxis, YAxis,
} from 'recharts';
import { fmtTime } from '../utils/format';

const number = (value, suffix = '') =>
  value == null ? '—' : `${Number(value).toLocaleString('ru-RU', { maximumFractionDigits: 1 })}${suffix}`;

const Kpi = ({ label, value, detail }) => (
  <div className="rounded-lg border border-slate-200 bg-slate-50/50 p-3 min-w-0">
    <div className="text-[11px] font-medium text-slate-500 mb-1">{label}</div>
    <div className="text-[24px] font-extrabold text-slate-800 tabular-nums leading-tight">{value}</div>
    <div className="text-[10px] text-slate-500 mt-1">{detail}</div>
  </div>
);

// Ни одного придуманного значения: сводка из /api/v1/analytics и /api/v1/state.
// Фактические выдачи и задержки берутся только из подтверждённой истории симулятора.
export default function ProductionAnalytics({ analytics, state, battery }) {
  const completed = analytics?.totalOperations ?? state?.counters?.discharges ?? null;
  const history = analytics?.recentOperations ?? [];
  const maxAllowedDelay = battery?.toleranceMin ?? 5;
  const chartData = history.slice(-15).map((item) => ({
    name: `П-${item.ovenNumber}`,
    delay: item.delayMin,
    ovenNumber: item.ovenNumber,
    plan: fmtTime(item.plannedAt),
    actual: fmtTime(item.actualAt),
  }));
  const machineLoad = analytics?.machineUtilization ?? [];
  const failures = state?.counters
    ? state.counters.pushJams + state.counters.ovenFaults
    : null;

  return (
    <section className="bg-white rounded-xl p-4 border border-slate-200 shadow-sm" aria-label="Производственная аналитика">
      <div className="flex justify-between items-start gap-4 flex-wrap mb-4">
        <div>
          <h3 className="font-bold text-[15px] text-slate-800">Производственная аналитика · текущий сценарий</h3>
          <p className="text-[11px] text-slate-500 mt-1">Текущий прогон симуляции с момента запуска или сброса, не заводская статистика</p>
        </div>
        <span className="text-[10px] text-slate-500 bg-slate-100 rounded-full px-3 py-1">
          {state?.running ? 'Обновляется в реальном времени' : 'Пауза / ожидание запуска'}
        </span>
      </div>

      <div className="grid grid-cols-2 lg:grid-cols-3 2xl:grid-cols-6 gap-3">
        <Kpi label="Выдано печей" value={number(completed)} detail="Фактически выполненные выдачи" />
        <Kpi label="В допуске" value={number(analytics?.onTimePct, '%')}
          detail={`Задержка не более ${number(maxAllowedDelay, ' мин')}`} />
        <Kpi label="Выдач с превышением" value={number(analytics?.delayedOperations)}
          detail="Факт позже плана сверх допуска" />
        <Kpi label="Средняя задержка" value={number(analytics?.avgDelayMin, ' мин')}
          detail="По всем выполненным выдачам" />
        <Kpi label="Интервал выдач" value={number(analytics?.avgIntervalMin, ' мин')}
          detail={`Норматив: ${number(analytics?.normIntervalMin ?? battery?.deltaMin, ' мин')}`} />
        <Kpi label="Отказы" value={number(failures)}
          detail="Выталкивание + обогрев печей" />
      </div>

      {!analytics && (
        <p className="mt-4 text-[12px] text-amber-700 bg-amber-50 border border-amber-200 rounded-lg p-3">
          Детальная аналитика пока недоступна: требуется обновить бэкенд с новым GET /api/v1/analytics.
          Базовые счётчики выше поступают из /api/v1/state.
        </p>
      )}

      {analytics && (
        <div className="grid grid-cols-1 2xl:grid-cols-2 gap-5 mt-5">
          <div className="rounded-lg border border-slate-200 p-3 min-w-0">
            <h4 className="text-[12px] font-bold text-slate-700">Отклонение факта от плана · последние {chartData.length} выдач</h4>
            <p className="text-[10px] text-slate-400 mt-1 mb-3">Минуты задержки. Пунктир — допустимый порог.</p>
            {chartData.length > 0 ? (
              <div className="h-[170px] w-full">
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={chartData} margin={{ top: 10, right: 5, left: -25, bottom: 5 }}>
                    <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#E2E8F0" />
                    <XAxis dataKey="name" tick={{ fontSize: 9, fill: '#64748B' }} interval="preserveStartEnd" />
                    <YAxis tick={{ fontSize: 9, fill: '#64748B' }} allowDecimals={false} />
                    <Tooltip content={({ active, payload }) => {
                      if (!active || !payload?.length) return null;
                      const item = payload[0].payload;
                      return <div className="bg-white border border-slate-200 shadow rounded p-2 text-[11px]">
                        <div className="font-bold mb-1">Печь {item.name}</div>
                        <div>План: {item.plan}</div><div>Факт: {item.actual}</div>
                        <div>Задержка: {number(item.delay, ' мин')}</div>
                      </div>;
                    }} />
                    <ReferenceLine y={maxAllowedDelay} stroke="#F97316" strokeDasharray="4 3" />
                    <Bar dataKey="delay" radius={[3, 3, 0, 0]} maxBarSize={28}>
                      {chartData.map((item, i) => (
                        <Cell key={`${item.ovenNumber}-${i}`}
                          fill={item.delay > maxAllowedDelay ? '#F97316' : '#2563EB'} />
                      ))}
                    </Bar>
                  </BarChart>
                </ResponsiveContainer>
              </div>
            ) : <div className="h-[170px] flex items-center justify-center text-slate-400 text-[12px]">Выдачи ещё не начались. Нажмите «Пуск».</div>}
          </div>

          <div className="rounded-lg border border-slate-200 p-3 min-w-0">
            <h4 className="text-[12px] font-bold text-slate-700 mb-2">План / факт · последние операции</h4>
            {history.length > 0 ? (
              <div className="overflow-x-auto">
                <table className="w-full text-[11px] text-left">
                  <thead className="text-slate-500 border-b border-slate-200">
                    <tr><th className="py-2">Печь</th><th>План</th><th>Факт</th><th className="text-right">Δ, мин</th></tr>
                  </thead>
                  <tbody className="text-slate-700 tabular-nums">
                    {history.slice(-7).reverse().map((item, i) => (
                      <tr className="border-b border-slate-100 last:border-0" key={`${item.ovenNumber}-${item.actualAt}-${i}`}>
                        <td className="py-2 font-semibold">П-{item.ovenNumber}</td>
                        <td>{fmtTime(item.plannedAt)}</td><td>{fmtTime(item.actualAt)}</td>
                        <td className={`text-right font-semibold ${item.onTime ? 'text-slate-600' : 'text-orange-600'}`}>+{number(item.delayMin)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            ) : <div className="h-[170px] flex items-center justify-center text-slate-400 text-[12px]">Нет завершённых операций</div>}
          </div>

          {machineLoad.length > 0 && (
            <div className="2xl:col-span-2 rounded-lg border border-slate-200 p-3">
              <h4 className="text-[12px] font-bold text-slate-700 mb-3">Загрузка оборудования · доля занятого времени</h4>
              <div className="grid grid-cols-1 md:grid-cols-2 2xl:grid-cols-4 gap-3">
                {machineLoad.map((m) => (
                  <div className="min-w-0" key={m.type}>
                    <div className="flex justify-between gap-3 text-[11px] mb-1">
                      <span className="truncate text-slate-600" title={m.name}>{m.name}</span>
                      <span className="font-bold tabular-nums">{number(m.utilizationPct, '%')}</span>
                    </div>
                    <div className="bg-slate-100 h-2 rounded-full overflow-hidden">
                      <div className="h-full rounded-full bg-blue-500 transition-[width] duration-700"
                        style={{ width: `${Math.max(0, Math.min(100, m.utilizationPct))}%` }} />
                    </div>
                  </div>
                ))}
              </div>
              <div className="mt-2 text-[10px] text-slate-400">Рассчитано по уже прошедшим интервалам движения и операций, без будущих задач.</div>
            </div>
          )}
        </div>
      )}
    </section>
  );
}
