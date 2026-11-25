import { useCallback, useEffect, useMemo, useState } from 'react';
import { getActiveRun, getRunOperations, manualEditOperation } from '../api/client';

// Шкала времени производственного графика: ближайшие операции действующего
// прогона на горизонтальной оси — плановое время, статус и сдвиг (правка
// куратора #26/#30). Лента событий остаётся ниже. Клик по операции — ручной
// перенос с обязательной причиной.
const WINDOW_MIN = 120;                 // окно шкалы: 2 часа модельного времени
// Часы предприятия: Asia/Novosibirsk = UTC+7 без перехода на летнее время.
// Всё считается в «псевдо-UTC» часов предприятия: модельное время сдвигается
// на +7 ч и сравнивается напрямую с плановым временем операций.
const PLANT_OFFSET_MS = 7 * 3600 * 1000;
const STATUS = {
  PLANNED: { label: 'Запланировано', color: '#94A3B8', chip: 'bg-slate-100 text-slate-600 border-slate-300' },
  IN_PROGRESS: { label: 'Выполняется', color: '#2563EB', chip: 'bg-blue-50 text-blue-700 border-blue-300' },
  DONE: { label: 'Выполнено', color: '#10B981', chip: 'bg-emerald-50 text-emerald-700 border-emerald-300' },
  OVERDUE: { label: 'Просрочено', color: '#EAB308', chip: 'bg-yellow-50 text-yellow-700 border-yellow-400' },
  IMPOSSIBLE: { label: 'Невозможно', color: '#F97316', chip: 'bg-orange-50 text-orange-700 border-orange-300' },
};

export default function TimeScale({ simTime }) {
  const [ops, setOps] = useState([]);
  const [runInfo, setRunInfo] = useState(null);
  const [editing, setEditing] = useState(null);
  const [error, setError] = useState('');

  const refresh = useCallback(() => {
    getActiveRun()
      .then((run) => {
        if (!run?.id) return;
        setRunInfo(run);
        return getRunOperations(run.id).then(setOps);
      })
      .catch(() => {});
  }, []);

  useEffect(() => {
    refresh();
    const timer = setInterval(refresh, 5000);
    return () => clearInterval(timer);
  }, [refresh]);

  // модельная минута сейчас — из живого снимка (шкала едет вместе с симулятором)
  const parsed = Date.parse(simTime ?? '');
  const nowMs = Number.isFinite(parsed) ? parsed + PLANT_OFFSET_MS : NaN;
  const hasTime = Number.isFinite(nowMs);
  const windowEnd = nowMs + WINDOW_MIN * 60000;

  const visible = useMemo(() => ops
    .map((op) => ({ ...op, startMs: Date.parse(op.plannedStart + 'Z') }))
    .filter((op) => Number.isFinite(op.startMs) && op.startMs <= windowEnd)
    .sort((a, b) => a.startMs - b.startMs)
    .slice(0, 24), [ops, windowEnd]);

  const frac = (ms) => Math.max(0, Math.min(1, (ms - nowMs) / (WINDOW_MIN * 60000)));

  return (
    <div className="bg-white rounded-xl p-4 border border-slate-200 shadow-sm">
      <div className="flex justify-between items-center mb-3 flex-wrap gap-2">
        <h3 className="font-bold text-[15px] text-slate-800">Шкала времени · график выдачи</h3>
        <div className="flex items-center gap-3 flex-wrap text-[11px] text-slate-600">
          {runInfo && (
            <span className="font-semibold">
              прогон {runInfo.id.slice(0, 8)} · {runInfo.algorithmTitle} · схема {runInfo.seriality}
            </span>
          )}
          {Object.entries(STATUS).map(([code, s]) => (
            <span key={code} className="flex items-center gap-1">
              <span className="w-2.5 h-2.5 rounded-[2px] inline-block" style={{ backgroundColor: s.color }}></span>
              {s.label}
            </span>
          ))}
        </div>
      </div>

      {visible.length === 0 ? (
        <div className="text-center py-6 text-slate-400 text-[13px] bg-slate-50 rounded-lg border border-dashed border-slate-300">
          График ещё не сформирован — нажмите «Сформировать график».
        </div>
      ) : (
        <div className="relative h-[74px] select-none" data-testid="time-scale">
          {/* ось */}
          <div className="absolute left-0 right-0 top-[34px] border-t-2 border-slate-300"></div>
          {/* деления: каждые 30 мин от начала окна */}
          {hasTime && Array.from({ length: Math.ceil(WINDOW_MIN / 30) + 1 }, (_, i) => {
            const t = new Date(nowMs + i * 30 * 60000);
            return (
              <div key={i} className="absolute top-[34px]" style={{ left: `${(i * 30 / WINDOW_MIN) * 100}%` }}>
                <div className="w-[1px] h-2 bg-slate-300 -translate-x-1/2"></div>
                <div className="text-[9px] text-slate-400 font-medium -translate-x-1/2 mt-0.5 whitespace-nowrap">
                  {String(t.getUTCHours()).padStart(2, '0')}:{String(t.getUTCMinutes()).padStart(2, '0')}
                </div>
              </div>
            );
          })}
          {/* операции */}
          {visible.map((op) => {
            const meta = STATUS[op.status] ?? STATUS.PLANNED;
            const past = op.startMs < nowMs;
            const delay = op.deviationMinutes ?? 0;
            const late = ['OVERDUE', 'IMPOSSIBLE'].includes(op.status) || delay > 5;
            return (
              <button
                key={op.id}
                type="button"
                onClick={() => op.status !== 'DONE' && setEditing(op)}
                title={`П-${op.ovenNumber} · ${meta.label}${delay ? `, отклонение ${delay > 0 ? '+' : ''}${delay} мин` : ''}${op.manual ? ', ручная правка' : ''}${op.status !== 'DONE' ? ' — клик для переноса' : ''}`}
                className="absolute top-[14px] -translate-x-1/2 flex flex-col items-center group"
                style={{ left: hasTime ? `${frac(op.startMs) * 100}%` : '50%' }}
              >
                <span className="text-[9px] font-bold text-slate-600 mb-0.5 whitespace-nowrap">П-{op.ovenNumber}</span>
                <span className="w-[9px] h-[9px] rounded-[2px] border-2 border-white shadow-sm group-hover:scale-150 transition-transform"
                  style={{ backgroundColor: meta.color, opacity: past && op.status === 'PLANNED' ? 0.5 : 1 }}></span>
                {late && (
                  <span className="text-[9px] font-bold text-[#C2410C] mt-0.5 whitespace-nowrap">
                    +{Math.round(delay)}
                  </span>
                )}
              </button>
            );
          })}
        </div>
      )}

      {editing && (
        <ManualEditModal
          op={editing}
          onClose={() => setEditing(null)}
          onDone={(text) => { setError(text); setEditing(null); refresh(); }}
        />
      )}
      {error && <div className="text-[11px] text-slate-500 mt-2">{error}</div>}
    </div>
  );
}

// Ручной перенос: новое время и обязательная причина (docs/03 PATCH).
const ManualEditModal = ({ op, onClose, onDone }) => {
  const [time, setTime] = useState(toLocalInput(op.plannedStart));
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const submit = async () => {
    if (!reason.trim()) {
      setError('Причина правки обязательна — без неё перенос не проходит');
      return;
    }
    setBusy(true);
    setError('');
    try {
      // введённое время — по часам предприятия (UTC+7): переводим в UTC
      const utcMs = Date.parse(time + 'Z') - PLANT_OFFSET_MS;
      await manualEditOperation(op.id, {
        plannedStart: new Date(utcMs).toISOString(),
        reason: reason.trim(),
      });
      onDone(`П-${op.ovenNumber} перенесена, план пересчитан`);
    } catch (e) {
      setError(e.message);
      setBusy(false);
    }
  };

  return (
    <div className="fixed inset-0 bg-slate-900/30 z-[90] flex items-center justify-center p-4" onClick={onClose}>
      <div className="bg-white rounded-xl shadow-xl w-[430px] p-6" onClick={(e) => e.stopPropagation()}>
        <h3 className="font-bold text-[15px] text-slate-800 mb-1">Ручной перенос выдачи</h3>
        <p className="text-[12px] text-slate-500 mb-4">
          Печь П-{op.ovenNumber} · план {String(op.plannedStart).slice(11, 16)}. Последующие
          операции сдвинутся позже; порядок печей не меняется.
        </p>
        <label className="block text-[11px] text-slate-500 mb-1 font-medium">Новое время</label>
        <input type="datetime-local" value={time} onChange={(e) => setTime(e.target.value)}
          className="w-full border border-slate-300 rounded-md p-2 text-[13px] mb-3" />
        <label className="block text-[11px] text-slate-500 mb-1 font-medium">Причина *</label>
        <textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={2}
          placeholder="Например: перенос по согласованию с цеховым мастером"
          className="w-full border border-slate-300 rounded-md p-2 text-[13px] mb-3" />
        {error && <div className="text-[11px] text-red-600 mb-3">{error}</div>}
        <div className="flex gap-2 justify-end">
          <button onClick={onClose} className="px-3 py-2 text-[13px] rounded-md border border-slate-300 text-slate-600 hover:bg-slate-50">Отмена</button>
          <button onClick={submit} disabled={busy}
            className="px-3 py-2 text-[13px] rounded-md bg-blue-600 text-white font-semibold hover:bg-blue-700 disabled:opacity-60">
            {busy ? 'Переношу…' : 'Перенести'}
          </button>
        </div>
      </div>
    </div>
  );
};

// plannedStart из БД — время по часам предприятия без зоны: показываем как есть.
const toLocalInput = (plantTime) => String(plantTime ?? '').slice(0, 16);
