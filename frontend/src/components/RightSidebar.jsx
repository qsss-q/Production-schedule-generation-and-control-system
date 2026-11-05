import React, { useEffect, useMemo, useState } from 'react';
import { getAudit } from '../api/client';
import { fmtDbTime, fmtTime } from '../utils/format';
import {
  deviationCardType,
  deviationText,
  deviationTitle,
  eventCategory,
  journalDot,
  journalText,
  recentDeviations,
} from '../utils/eventView';

// Теги-фильтры журнала: подпись, категория события, цвет
const TAGS = [
  { cat: 'accident', label: 'Авария', color: 'bg-red-100 text-red-600' },
  { cat: 'schedule', label: 'Срыв графика', color: 'bg-yellow-200 text-yellow-800' },
  { cat: 'deviation', label: 'Отклонение', color: 'bg-yellow-100 text-yellow-700' },
  { cat: 'info', label: 'Информация', color: 'bg-slate-100 text-slate-600' },
];

export default function RightSidebar({ onClose, events = [], state = null }) {
  const [category, setCategory] = useState(null);   // выбранный тег или null
  const [equip, setEquip] = useState('all');        // 'all' или номер печи
  const [period, setPeriod] = useState('480');      // минут симуляции или 'all'
  const [actions, setActions] = useState([]);       // журнал действий оператора (БД)

  const nowMin = state?.simMinutes ?? 0;

  // Действия оператора: GET /api/audit (обновляем, пока панель открыта)
  useEffect(() => {
    let alive = true;
    const load = () =>
      getAudit()
        .then((rows) => alive && setActions([...rows].sort((a, b) => b.id - a.id).slice(0, 8)))
        .catch(() => {});
    load();
    const timer = setInterval(load, 3000);
    return () => { alive = false; clearInterval(timer); };
  }, []);

  // «Отклонения»: события, требующие внимания, за последний час симуляции
  const deviations = recentDeviations(events, nowMin).slice(0, 6);

  // печи, по которым были события (для выпадающего списка)
  const ovenOptions = useMemo(
    () => [...new Set(events.map((e) => e.ovenNumber))].sort((a, b) => a - b),
    [events]
  );

  const filtered = events.filter(
    (e) =>
      (!category || eventCategory(e) === category) &&
      (equip === 'all' || e.ovenNumber === Number(equip)) &&
      (period === 'all' || e.simMinutes >= nowMin - Number(period))
  );

  const reset = () => { setCategory(null); setEquip('all'); setPeriod('480'); };

  return (
    <div className="w-[360px] min-w-[300px] max-w-[500px] bg-[#F8FAFC] border-l border-slate-200 h-full flex flex-col flex-shrink-0 z-20 shadow-[-4px_0_15px_-3px_rgba(0,0,0,0.05)] overflow-y-auto">
      
      <div className="p-4 flex justify-between items-center sticky top-0 bg-[#F8FAFC]/90 backdrop-blur-sm z-10 border-b border-slate-200/50">
        <h2 className="text-[15px] font-bold text-slate-800">Уведомления и журналы</h2>
        <button onClick={onClose} className="text-slate-400 hover:text-slate-700 transition-colors p-1 rounded-md hover:bg-slate-200">
          <svg width="14" height="14" viewBox="0 0 14 14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M13 1L1 13M1 1l12 12"/></svg>
        </button>
      </div>

      <div className="p-4 space-y-4">
        {/* Секция Отклонения */}
        <div className="bg-white border border-slate-200 rounded-xl p-4 shadow-sm">
          <div className="flex justify-between items-center mb-3">
            <h3 className="font-bold text-[13px] text-slate-800">Отклонения</h3>
            <span className="bg-slate-100 text-slate-500 text-[10px] px-2 py-0.5 rounded-full font-semibold">{deviations.length} новых</span>
          </div>
          <div className="space-y-2">
            {deviations.length > 0 ? deviations.map((e, i) => (
              <DeviationCard key={`${e.simMinutes}-${e.type}-${e.ovenNumber}-${i}`} type={deviationCardType[eventCategory(e)]} title={deviationTitle(e)} desc={deviationText(e)} />
            )) : (
              <div className="text-[11px] text-slate-400">За последний час отклонений нет.</div>
            )}
          </div>
        </div>

        {/* Секция Журнал событий */}
        <div className="bg-white border border-slate-200 rounded-xl p-4 shadow-sm">
          <div className="flex justify-between items-center mb-3">
            <h3 className="font-bold text-[13px] text-slate-800">Журнал событий</h3>
            <span className="text-slate-400 text-[10px]">найдено: {filtered.length}</span>
          </div>
          <div className="flex gap-2 mb-3">
            <div className="flex-1">
              <label className="text-[10px] text-slate-400 mb-1 block">Оборудование</label>
              <select value={equip} onChange={(e) => setEquip(e.target.value)} className="w-full border border-slate-200 rounded p-1.5 text-[11px] text-slate-700 bg-slate-50 cursor-pointer focus:outline-none focus:border-blue-400 hover:border-slate-300">
                <option value="all">Всё оборудование</option>
                {ovenOptions.map((n) => <option key={n} value={n}>Печь П-{n}</option>)}
              </select>
            </div>
            <div className="w-[100px]">
              <label className="text-[10px] text-slate-400 mb-1 block">Период</label>
              <select value={period} onChange={(e) => setPeriod(e.target.value)} className="w-full border border-slate-200 rounded p-1.5 text-[11px] text-slate-700 bg-slate-50 cursor-pointer focus:outline-none focus:border-blue-400 hover:border-slate-300">
                <option value="480">Смена (8 ч)</option>
                <option value="240">4 ч</option>
                <option value="all">Всё время</option>
              </select>
            </div>
          </div>
          
          <div className="flex flex-wrap gap-1.5 mb-3">
            {TAGS.map((t) => (
              <Tag key={t.cat} color={t.color} active={category === t.cat} onClick={() => setCategory(category === t.cat ? null : t.cat)}>{t.label}</Tag>
            ))}
          </div>
          <div className="flex gap-2 text-[10px] font-semibold text-blue-600 mb-4 border-b border-slate-100 pb-2">
            <button onClick={() => setCategory('accident')} className="hover:text-blue-800">Аварии выбранной печи</button>
            <button onClick={() => { setCategory('schedule'); setEquip('all'); setPeriod('240'); }} className="hover:text-blue-800">Срывы всех печей · 4 ч</button>
            <button onClick={reset} className="hover:text-blue-800 ml-auto">Сбросить</button>
          </div>

          <div className="space-y-3 relative before:absolute before:inset-0 before:ml-[5px] before:-translate-x-px md:before:mx-auto md:before:translate-x-0 before:h-full before:w-0.5 before:bg-slate-100">
            {filtered.slice(0, 12).map((e, i) => (
              <TimelineItem key={`${e.simMinutes}-${e.type}-${e.ovenNumber}-${i}`} time={fmtTime(e.simTime)} color={journalDot(e)} text={journalText(e)} />
            ))}
            {filtered.length === 0 && <div className="text-[11px] text-slate-400 relative z-10 bg-white">Событий по выбранным фильтрам нет.</div>}
          </div>
        </div>

        {/* Секция Действия оператора (журнал из БД) */}
        <div className="bg-white border border-slate-200 rounded-xl p-4 shadow-sm">
          <h3 className="font-bold text-[13px] text-slate-800 mb-4">Действия оператора</h3>
          <div className="space-y-3">
            {actions.map((a) => (
              <OpAction key={a.id} time={fmtDbTime(a.createdAt)} text={a.details} />
            ))}
            {actions.length === 0 && <div className="text-[11px] text-slate-400">Пока нет действий оператора.</div>}
          </div>
        </div>
      </div>
    </div>
  );
}

const DeviationCard = ({ type, title, desc }) => {
  const styles = {
    error: 'bg-red-50 border-red-200 text-red-900 before:bg-red-500',
    warning: 'bg-[#FEF9C3]/50 border-yellow-200 text-yellow-900 before:bg-yellow-500',
    alert: 'bg-yellow-50/50 border-yellow-100 text-slate-800 before:bg-yellow-300',
    info: 'bg-slate-50 border-slate-200 text-slate-700 before:bg-slate-400'
  };
  return (
    <div className={`relative rounded-lg p-2.5 pl-4 border text-[11px] hover:shadow-sm transition-shadow cursor-pointer overflow-hidden ${styles[type]}`}>
      <div className={`absolute left-0 top-0 bottom-0 w-1 ${styles[type].split(' ').find(c => c.startsWith('before:bg')).replace('before:', '')}`}></div>
      <div className="font-bold mb-0.5">{title}</div>
      <div className="opacity-80">{desc}</div>
    </div>
  );
};
const Tag = ({ children, color, active, onClick }) => <span onClick={onClick} className={`text-[9px] px-2 py-0.5 rounded-full font-bold cursor-pointer hover:opacity-80 transition-opacity ${color} ${active ? 'ring-2 ring-offset-1 ring-slate-400' : ''}`}>{children}</span>;
const TimelineItem = ({ time, color, text }) => (
  <div className="flex items-center gap-3 relative z-10 group cursor-pointer">
    <div className={`w-2.5 h-2.5 rounded-full border-2 border-white shadow-sm group-hover:scale-125 transition-transform ${color}`}></div>
    <span className="font-bold text-[11px] text-slate-800">{time}</span><span className="text-[11px] text-slate-600 group-hover:text-slate-900 transition-colors">{text}</span>
  </div>
);
const OpAction = ({ time, text }) => (
  <div className="flex items-center gap-3 group cursor-pointer">
    <div className="w-2 h-2 bg-[#3B82F6] rounded-sm group-hover:scale-125 transition-transform"></div>
    <span className="font-bold text-[11px] text-slate-800">{time}</span><span className="text-[11px] text-slate-600 group-hover:text-slate-900 transition-colors">{text}</span>
  </div>
);
