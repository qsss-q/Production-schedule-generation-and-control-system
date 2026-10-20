import React, { useState } from 'react';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, ResponsiveContainer, ReferenceLine } from 'recharts';

// Единая логика данных для печей
const getOvenData = (num) => {
  const isGreen = [1, 10, 15, 31, 51].includes(num);
  const isYellow = [2, 28, 43, 58].includes(num);
  const isOrange = [3, 9, 12, 13, 26, 27, 40, 41, 42, 60].includes(num);
  const isRed = [30].includes(num);

  if (isGreen) return { color: '#10B981', pc: 100, stat: 'Готова к выгрузке', txt: 'text-[#10B981]', border: 'border-[#10B981]' };
  if (isYellow) return { color: '#EAB308', pc: 58, stat: 'Коксование', txt: 'text-[#A16207]', border: 'border-[#EAB308]' };
  if (isOrange) return { color: '#F97316', pc: 100, over: true, stat: 'Передержка кокса', txt: 'text-[#C2410C]', border: 'border-[#F97316]' };
  if (isRed) return { color: '#EF4444', pc: 0, stat: 'Авария', txt: 'text-[#EF4444]', border: 'border-[#EF4444]' };
  return { color: '#3B82F6', pc: 40, stat: 'Выдача кокса', txt: 'text-[#1D4ED8]', border: 'border-[#3B82F6]' };
};

export default function MainWorkspace() {
  const [selectedOvens, setSelectedOvens] = useState([]); // 
  const [analytics, setAnalytics] = useState([]);
  const [pusherPos] = useState(2); 
  const [wagonPos] = useState(4);  

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

  return (
    <div className="p-6 space-y-6">
      
      {/* 1. Блок: Вся батарея (Маленькие печи) */}
      <div className="bg-white rounded-xl p-2 border border-slate-200 shadow-sm">
        <div className="flex justify-between items-center mb-6">
          <h3 className="font-bold text-[15px] text-slate-800">Вся батарея · 60 печей</h3>
          <div className="flex gap-4 text-[11px] font-semibold text-slate-600">
            <span className="flex items-center gap-1.5"><div className="w-2.5 h-2.5 bg-[#3B82F6] rounded-[2px]"></div>В работе: 44</span>
            <span className="flex items-center gap-1.5"><div className="w-2.5 h-2.5 bg-[#EAB308] rounded-[2px]"></div>Отставание: 12</span>
            <span className="flex items-center gap-1.5"><div className="w-2.5 h-2.5 bg-[#A16207] rounded-[2px]"></div>Срыв графика: 3</span>
            <span className="flex items-center gap-1.5"><div className="w-2.5 h-2.5 bg-[#EF4444] rounded-[2px]"></div>Авария: 1</span>
          </div>
        </div>

        <div className="space-y-6">
          <div>
            <TrackWithMachines start={1} end={30} pusherPos={pusherPos} wagonPos={wagonPos} />
            <div className="flex justify-between w-full mt-1 px-2">
              {Array.from({ length: 30 }).map((_, i) => (<MiniOven key={i+1} num={i+1} isSelected={selectedOvens.includes(i+1)} onClick={() => toggleOven(i+1)} />))}
            </div>
          </div>
          <div>
            <TrackWithMachines start={31} end={60} pusherPos={pusherPos} wagonPos={wagonPos} />
            <div className="flex justify-between w-full mt-1 px-2">
              {Array.from({ length: 30 }).map((_, i) => (<MiniOven key={i+31} num={i+31} isSelected={selectedOvens.includes(i+31)} onClick={() => toggleOven(i+31)} />))}
            </div>
          </div>
        </div>
      </div>

      {/* 2. Блок: Большие карточки выбранных печей */}
      <div className="bg-white rounded-xl p-3 border border-slate-200 shadow-sm">
        <div className="flex justify-between items-center mb-5">
          <h3 className="font-bold text-[15px] text-slate-800">Печи · ход процесса и состояние</h3>
          <div className="flex items-center gap-4 text-[11px]">
            <span className="text-slate-500 font-medium">включено {selectedOvens.length} из 60</span>
            <button className="text-blue-600 border border-blue-200 bg-blue-50 hover:bg-blue-100 transition-colors px-3 py-1.5 rounded-md font-bold">Авто: самые проблемные</button>
          </div>
        </div>
        
        {selectedOvens.length > 0 ? (
          <div className="grid grid-cols-5 gap-4">
            {selectedOvens.map(num => (
              <BigOvenCard 
                key={num} 
                num={num} 
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
            {analytics.map(num => <AnalyticsChartCard key={num} num={num} onClose={() => toggleAnalytics(num)} />)}
          </div>
        </div>
      )}

      {/* 4. Блок: Лента событий */}
      <div className="bg-white rounded-xl p-5 border border-slate-200 shadow-sm">
        <h3 className="font-bold text-[15px] text-slate-800 mb-5">Лента освещает события</h3>
        <div className="space-y-4">
          <EventRow time="11:37" dot="bg-[#3B82F6]" text="П-4 · завершение выгрузки" rightText="через 27 мин" />
          <EventRow time="12:30" dot="bg-[#F97316]" text="П-3 · выдача кокса (ручная)" rightText="+25 мин" highlight />
          <EventRow time="17:03" dot="bg-[#EAB308]" text="П-2 · выдача кокса" rightText="+6 мин" highlight />
        </div>
      </div>
    </div>
  );
}

// === ВСПОМОГАТЕЛЬНЫЕ КОМПОНЕНТЫ ===

const TrackWithMachines = ({ start, end, pusherPos, wagonPos }) => {
  const showPusher = pusherPos >= start && pusherPos <= end;
  const showWagon = wagonPos >= start && wagonPos <= end;
  return (
    <div className="relative w-full h-8 border-b-[3px] border-dotted border-slate-300">
      {showPusher && (
        <div className="absolute top-0 flex flex-col items-center transition-all z-10" style={{ left: `${((pusherPos - start) / 29) * 100}%`, transform: 'translateX(-50%)' }}>
          <span className="text-[9px] font-bold bg-white text-[#EF4444] px-1.5 py-0.5 rounded shadow-[0_2px_4px_rgba(0,0,0,0.1)] border border-[#EF4444]">Выталкиватель</span>
          <div className="w-0 h-0 border-l-[5px] border-r-[5px] border-t-[6px] border-x-transparent border-t-[#EF4444] mt-0.5"></div>
        </div>
      )}
      {showWagon && (
        <div className="absolute top-0 flex flex-col items-center transition-all z-10" style={{ left: `${((wagonPos - start) / 29) * 100}%`, transform: 'translateX(-50%)' }}>
          <span className="text-[9px] font-bold bg-white text-[#10B981] px-1.5 py-0.5 rounded shadow-[0_2px_4px_rgba(0,0,0,0.1)] border border-[#10B981]">Тушил. вагон</span>
          <div className="w-0 h-0 border-l-[5px] border-r-[5px] border-t-[6px] border-x-transparent border-t-[#10B981] mt-0.5"></div>
        </div>
      )}
    </div>
  );
};

// Исправленная мини-печь: просто строгая темная обводка и увеличение при клике
const MiniOven = ({ num, isSelected, onClick }) => {
  const data = getOvenData(num);
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
const BigOvenCard = ({ num, onAnalyticsToggle, isAnalyticsOpen }) => {
  const t = getOvenData(num);
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
            height: typeof t.pc === 'string' ? '100%' : `${t.pc}%`, 
            backgroundColor: t.color, 
            backgroundImage: 'repeating-linear-gradient(90deg, transparent, transparent 5px, rgba(0,0,0,0.15) 5px, rgba(0,0,0,0.15) 7px)' 
          }}></div>
        </div>
      </div>
      
      <span className={`text-[24px] font-black ${t.txt} leading-none mb-1.5`}>{t.pc}{typeof t.pc === 'number' ? ' %' : ' %'}</span>
      <span className="text-[11px] text-slate-500 font-medium mb-1 text-center">{t.stat}</span>
    </div>
  );
};

// Карточка с самим графиком
const AnalyticsChartCard = ({ num, onClose }) => {
  const t = getOvenData(num);
  const data = [ {time: '08:00', val: 990}, {time: '09:00', val: 1000}, {time: '10:00', val: 1010}, {time: '11:00', val: 1008} ];
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