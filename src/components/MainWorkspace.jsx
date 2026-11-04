import React, { useEffect, useMemo, useState } from 'react';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, ResponsiveContainer, ReferenceLine } from 'recharts';
import { getTemperature } from '../api/client';
import { fmtTime } from '../utils/format';
import { LEGEND, legendColor, ovenView } from '../utils/ovenView';

// Положение машины в ряду печей. pos — номер печи (может быть дробным,
// у башен больше числа печей — тогда показываем у последней печи).
const machinePos = (machines, type, total) => {
  const m = machines.find((x) => x.type === type);
  return m ? Math.min(m.pos, total) : null;
};

export default function MainWorkspace({ ovens = [], machines = [], feed = null }) {
  const [selectedOvens, setSelectedOvens] = useState([]); // 
  const [analytics, setAnalytics] = useState([]);

  const total = ovens.length;
  const ovenByNum = useMemo(() => Object.fromEntries(ovens.map((o) => [o.number, o])), [ovens]);
  // батарея рисуется двумя рядами: 69 печей -> 35 + 34
  const split = Math.ceil(total / 2);
  const pusherPos = machinePos(machines, 'pusher', total);
  const wagonPos = machinePos(machines, 'quenchCar', total);

  const counts = ovens.reduce((acc, o) => ({ ...acc, [o.state]: (acc[o.state] ?? 0) + 1 }), {});

  // Клик по маленькой печи сверху
  const toggleOven = (num) => {
    setSelectedOvens(prev => {
      const isSelected = prev.includes(num);
      if (isSelected) {
        // Если снимаем выделение с печи, то закрываем и её график аналитики
        setAnalytics(a => a.filter(n => n !== num));
        return prev.filter(n => n !== num);
      } else {
        return [...prev, num].sort((a, b) => a - b);
      }
    });
  };

  // Клик по иконке графика внутри большой карточки
  const toggleAnalytics = (num) => {
    setAnalytics(prev => prev.includes(num) ? prev.filter(n => n !== num) : [...prev, num]);
  };

  // «Авто: самые проблемные» — до 5 печей: сначала аварии, затем самые долгие передержки
  const autoSelect = () => {
    const rank = { fault: 0, overhold: 1 };
    const problem = ovens
      .filter((o) => o.state === 'fault' || o.state === 'overhold')
      .sort((a, b) => rank[a.state] - rank[b.state] || b.deviationMin - a.deviationMin)
      .slice(0, 5)
      .map((o) => o.number)
      .sort((a, b) => a - b);
    setSelectedOvens(problem);
    setAnalytics((prev) => prev.filter((n) => problem.includes(n)));
  };

  const feedItems = (feed?.items ?? []).slice(0, 6);

  return (
    <div className="p-6 space-y-6">
      
      {/* 1. Блок: Вся батарея (Маленькие печи) */}
      <div className="bg-white rounded-xl p-2 border border-slate-200 shadow-sm">
        <div className="flex justify-between items-center mb-6">
          <h3 className="font-bold text-[15px] text-slate-800">Вся батарея · {total} печей</h3>
          <div className="flex gap-4 text-[11px] font-semibold text-slate-600">
            {LEGEND.map((item) => (
              <span key={item.state} className="flex items-center gap-1.5"><div className="w-2.5 h-2.5 rounded-[2px]" style={{ backgroundColor: legendColor(item.state) }}></div>{item.label}: {counts[item.state] ?? 0}</span>
            ))}
          </div>
        </div>

        {total === 0 ? (
          <div className="text-center py-10 text-slate-400 text-sm font-medium">Загрузка данных симулятора…</div>
        ) : (
          <div className="space-y-6">
            <div>
              <TrackWithMachines start={1} end={split} isLast={false} pusherPos={pusherPos} wagonPos={wagonPos} />
              <div className="flex justify-between w-full mt-1 px-2">
                {ovens.slice(0, split).map((o) => (<MiniOven key={o.number} oven={o} isSelected={selectedOvens.includes(o.number)} onClick={() => toggleOven(o.number)} />))}
              </div>
            </div>
            <div>
              <TrackWithMachines start={split + 1} end={total} isLast={true} pusherPos={pusherPos} wagonPos={wagonPos} />
              <div className="flex justify-between w-full mt-1 px-2">
                {ovens.slice(split).map((o) => (<MiniOven key={o.number} oven={o} isSelected={selectedOvens.includes(o.number)} onClick={() => toggleOven(o.number)} />))}
              </div>
            </div>
          </div>
        )}
      </div>

      {/* 2. Блок: Большие карточки выбранных печей */}
      <div className="bg-white rounded-xl p-3 border border-slate-200 shadow-sm">
        <div className="flex justify-between items-center mb-5">
          <h3 className="font-bold text-[15px] text-slate-800">Печи · ход процесса и состояние</h3>
          <div className="flex items-center gap-4 text-[11px]">
            <span className="text-slate-500 font-medium">включено {selectedOvens.length} из {total}</span>
            <button onClick={autoSelect} className="text-blue-600 border border-blue-200 bg-blue-50 hover:bg-blue-100 transition-colors px-3 py-1.5 rounded-md font-bold">Авто: самые проблемные</button>
          </div>
        </div>
        
        {selectedOvens.length > 0 ? (
          <div className="grid grid-cols-5 gap-4">
            {selectedOvens.filter((num) => ovenByNum[num]).map(num => (
              <BigOvenCard 
                key={num} 
                num={num} 
                oven={ovenByNum[num]}
                onAnalyticsToggle={toggleAnalytics} 
                isAnalyticsOpen={analytics.includes(num)} 
              />
            ))}
          </div>
        ) : (
          <div className="text-center py-10 text-slate-400 text-sm font-medium bg-slate-50 rounded-lg border border-dashed border-slate-300">
            Нет включенных печей. Кликните по печи на панели сверху.
          </div>
        )}
      </div>

      {/* 3. Блок: Графики Аналитики (Появляются под карточками, над лентой событий) */}
      {analytics.length > 0 && (
        <div className="bg-slate-50 border border-slate-200 rounded-lg p-5 transition-all">
          <div className="flex justify-between items-center mb-4">
            <h3 className="font-bold text-[15px]">Аналитика печей - сравнение (открыто {analytics.length})</h3>
          </div>
          <div className="grid grid-cols-2 gap-4">
            {analytics.filter((num) => ovenByNum[num]).map(num => <AnalyticsChartCard key={num} num={num} oven={ovenByNum[num]} onClose={() => toggleAnalytics(num)} />)}
          </div>
        </div>
      )}

      {/* 4. Блок: Лента событий */}
      <div className="bg-white rounded-xl p-5 border border-slate-200 shadow-sm">
        <h3 className="font-bold text-[15px] text-slate-800 mb-5">Лента освещает события</h3>
        <div className="space-y-4">
          {feedItems.length > 0 ? feedItems.map((it) => (
            <EventRow
              key={`${it.ovenNumber}-${it.plannedAt}`}
              time={fmtTime(it.predictedAt)}
              dot={it.severity === 'warning' ? 'bg-[#F97316]' : 'bg-[#3B82F6]'}
              text={it.title}
              rightText={it.deviationMin > 0 ? `+${Math.round(it.deviationMin)} мин` : it.relativeLabel}
              highlight={it.deviationMin > 0}
            />
          )) : (
            <div className="text-slate-400 text-[13px]">Нет данных от симулятора.</div>
          )}
        </div>
      </div>
    </div>
  );
}

// === ВСПОМОГАТЕЛЬНЫЕ КОМПОНЕНТЫ ===


// Машины на дорожке над рядом печей. Ряд занимает печи start..end;
// у последнего ряда справа машина доезжает до конца (башни за торцом батареи).
const TrackWithMachines = ({ start, end, isLast, pusherPos, wagonPos }) => {
  // Расширяем зону видимости: вагон живет в DOM, даже если он на 1 печь за пределами ряда
  const inRow = (pos) => pos != null && pos >= start - 1 && pos <= end + 1;
  
  const getStyle = (pos) => {
    if (pos == null) return { opacity: 0, left: '0%' };
    
    const span = Math.max(1, end - start);
    // Убрали жесткие лимиты Math.min/max. 
    // Теперь left честно просчитывается в минус (выезжает) или больше 100% (уезжает)
    const left = ((pos - start) / span) * 100;
    
    return {
      left: `${left}%`,
      transform: 'translateX(-50%)',
      opacity: inRow(pos) ? 1 : 0,
      pointerEvents: inRow(pos) ? 'auto' : 'none'
    };
  };

  return (
    // Добавлено overflow-hidden: края дорожки теперь обрезают вагоны, создавая эффект туннеля
    <div className="relative w-full h-8 border-b-[3px] border-dotted border-slate-300 overflow-hidden">
      <div 
        // Убрали медленную анимацию, поставили duration-300: вагон больше не дергается и не отстает от бэкенда
        className="absolute top-0 flex flex-col items-center transition-all duration-300 ease-linear z-10" 
        style={getStyle(pusherPos)}
      >
        <span className="text-[9px] font-bold bg-white text-[#EF4444] px-1.5 py-0.5 rounded shadow-[0_2px_4px_rgba(0,0,0,0.1)] border border-[#EF4444]">Выталкиватель</span>
        <div className="w-0 h-0 border-l-[5px] border-r-[5px] border-t-[6px] border-x-transparent border-t-[#EF4444] mt-0.5"></div>
      </div>

      <div 
        className="absolute top-0 flex flex-col items-center transition-all duration-300 ease-linear z-20" 
        style={getStyle(wagonPos)}
      >
        <span className="text-[9px] font-bold bg-white text-[#10B981] px-1.5 py-0.5 rounded shadow-[0_2px_4px_rgba(0,0,0,0.1)] border border-[#10B981]">Тушил. вагон</span>
        <div className="w-0 h-0 border-l-[5px] border-r-[5px] border-t-[6px] border-x-transparent border-t-[#10B981] mt-0.5"></div>
      </div>
    </div>
  );
};
// Исправленная мини-печь: просто строгая темная обводка и увеличение при клике
const MiniOven = ({ oven, isSelected, onClick }) => {
  const num = oven.number;
  const data = ovenView(oven);
  return (
    <div className="flex flex-col items-center group relative cursor-pointer w-[18px]" onClick={onClick}>
      <div className={`
        w-[18px] h-[22px] rounded-t-[3px] transition-all border-2 flex items-end overflow-hidden bg-slate-100
        ${isSelected ? 'border-blue-600 scale-125 z-10' : 'border-slate-300 group-hover:border-slate-500'}
      `}>
        <div className="w-full transition-all duration-500" style={{ 
          height: `${data.pc}%`, backgroundColor: data.color, 
          backgroundImage: 'repeating-linear-gradient(90deg, transparent, transparent 2px, rgba(0,0,0,0.1) 2px, rgba(0,0,0,0.1) 3px)' 
        }}></div>
      </div>
      <span className={`text-[9px] mt-1.5 transition-colors ${isSelected ? 'text-slate-800 font-bold' : 'text-slate-400 font-medium group-hover:text-slate-600'}`}>{num}</span>
    </div>
  );
};

// Обновленная большая карточка под новый дизайн со скриншота
const BigOvenCard = ({ num, oven, onAnalyticsToggle, isAnalyticsOpen }) => {
  const t = ovenView(oven);
  return (
    <div className={`bg-white rounded-xl p-4 border-2 flex flex-col items-center relative transition-all ${isAnalyticsOpen ? 'shadow-md border-blue-200' : t.border}`}>
      <div className="w-full flex justify-between items-start mb-6">
        <span className="font-bold text-[13px] text-slate-800">Печь П- {num}</span>
        
        {/* Кнопка открытия аналитики и статусный кружок */}
        <div className="flex items-center gap-2">
          <button 
            onClick={() => onAnalyticsToggle(num)} 
            className={`w-7 h-7 rounded border flex items-center justify-center transition-colors ${isAnalyticsOpen ? 'bg-slate-100 border-slate-300 text-slate-700' : 'bg-white border-slate-200 text-slate-400 hover:text-slate-600 hover:border-slate-300'}`}
            title="Показать график"
          >
            {/* Иконка графика */}
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <polyline points="22 12 18 12 15 21 9 3 6 12 2 12"></polyline>
            </svg>
          </button>
          <div className="w-2.5 h-2.5 rounded-full" style={{ backgroundColor: t.color }}></div>
        </div>
      </div>
      
      {/* Рисунок печи с 3 трубами */}
      <div className="flex flex-col items-center mb-5">
        <div className="flex justify-center gap-1 z-10 relative -mb-[2px]">
          <div className="w-4 h-2.5 bg-white border-2 border-slate-300 border-b-0 rounded-t-[2px]"></div>
          <div className="w-4 h-2.5 bg-white border-2 border-slate-300 border-b-0 rounded-t-[2px]"></div>
          <div className="w-4 h-2.5 bg-white border-2 border-slate-300 border-b-0 rounded-t-[2px]"></div>
        </div>
        <div className="w-[70px] h-[80px] border-2 border-slate-300 rounded-[6px] relative overflow-hidden bg-white flex items-end">
          <div className="w-full transition-all duration-1000" style={{ 
            height: `${t.pc}%`, 
            backgroundColor: t.color, 
            backgroundImage: 'repeating-linear-gradient(90deg, transparent, transparent 5px, rgba(0,0,0,0.15) 5px, rgba(0,0,0,0.15) 7px)' 
          }}></div>
        </div>
      </div>
      
      <span className={`text-[24px] font-black ${t.txt} leading-none mb-1.5`}>{t.pc} %</span>
      <span className="text-[11px] text-slate-500 font-medium mb-1 text-center">{t.stat}</span>
      {oven.deviationMin > 0 && (
        <span className="text-[10px] font-bold text-[#F97316]">+{Math.round(oven.deviationMin)} мин</span>
      )}
    </div>
  );
};

// Запасные данные, если в БД нет замеров температуры для печи
const FALLBACK_TEMPERATURE = [ {time: '08:00', val: 990}, {time: '09:00', val: 1000}, {time: '10:00', val: 1010}, {time: '11:00', val: 1008} ];

// Карточка с самим графиком
const AnalyticsChartCard = ({ num, oven, onClose }) => {
  const t = ovenView(oven);
  const [readings, setReadings] = useState(null);

  // температура берётся из БД: GET /api/temperature/by-oven-number/{номер печи}
  useEffect(() => {
    let alive = true;
    getTemperature(num)
      .then((rows) => alive && setReadings(rows))
      .catch(() => alive && setReadings([]));
    return () => { alive = false; };
  }, [num]);

  const data = readings && readings.length > 0
    ? readings.map((r) => ({ time: String(r.recordedAt).slice(11, 16), val: r.temperature }))
    : FALLBACK_TEMPERATURE;

  return (
    <div className="bg-white border border-slate-200 rounded p-4 relative shadow-sm">
      <div className="flex justify-between items-center mb-2">
        <div className="font-bold text-[13px]">Печь П-{num} · Батарея №1</div>
        <div className="flex gap-2 items-center">
          <span className={`text-[10px] px-2 py-0.5 rounded-full font-bold bg-opacity-10 ${t.txt} ${t.border} border`}>{t.stat}</span>
          <button onClick={onClose} className="text-slate-400 hover:text-red-500 font-bold p-1">✕</button>
        </div>
      </div>
      <div className="text-[10px] text-slate-400 mb-4">Температура в камере, °C</div>
      <div className="h-24 w-full mb-4">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={data} margin={{ top: 5, right: 10, left: -25, bottom: 0 }}>
            <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#E2E8F0" />
            <XAxis dataKey="time" axisLine={false} tickLine={false} tick={{fontSize: 9, fill: '#94A3B8'}} />
            <YAxis domain={[950, 1050]} axisLine={false} tickLine={false} tick={{fontSize: 9, fill: '#94A3B8'}} />
            <ReferenceLine y={1008} stroke="#EAB308" strokeDasharray="3 3" />
            <Line type="monotone" dataKey="val" stroke="#0EA5E9" strokeWidth={2} dot={{r:3, fill: '#0EA5E9', strokeWidth: 0}} />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
};

const EventRow = ({ time, dot, text, rightText, highlight }) => (
  <div className="flex justify-between items-center text-[13px] border-b border-slate-50 pb-2 last:border-0 last:pb-0">
    <div className="flex items-center gap-4">
      <div className={`w-2.5 h-2.5 rounded-full ${dot} shadow-sm`}></div>
      <span className="font-bold text-slate-800 w-12">{time}</span><span className="text-slate-600 font-medium">{text}</span>
    </div>
    <span className={`text-[11px] font-bold ${highlight ? 'text-[#F97316]' : 'text-slate-400'}`}>{rightText}</span>
  </div>
);
