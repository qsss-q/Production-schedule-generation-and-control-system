import { useEffect, useState } from 'react';
import { LineChart, Line, XAxis, YAxis, CartesianGrid, ResponsiveContainer } from 'recharts';
import { getTemperature } from '../api/client';
import { fmtTime } from '../utils/format';
import { LEGEND, legendColor, ovenView } from '../utils/ovenView';

// Данные приборов и машин берём из живого API; здесь только визуализация.
export default function MainWorkspace({ ovens = [], machines = [], feed = null, state = null, battery = null }) {
  const [selectedOvens, setSelectedOvens] = useState([]); 
  const [openCharts, setOpenCharts] = useState([]);

  const total = ovens.length;
  // Перестраиваем индекс на КАЖДОМ новом снимке. Выбор печи хранит только номер, не устаревший объект.
  const ovenByNum = Object.fromEntries(ovens.map((o) => [o.number, o]));
  // батарея рисуется двумя рядами: 69 печей -> 35 + 34
  const split = Math.ceil(total / 2);

  const counts = ovens.reduce((acc, o) => ({ ...acc, [o.state]: (acc[o.state] ?? 0) + 1 }), {});

  // Клик по маленькой печи сверху
  const toggleOven = (num) => {
    // Не запускаем setState из другого state-updater (StrictMode может вызвать его дважды).
    if (selectedOvens.includes(num)) {
      setOpenCharts((prev) => prev.filter((n) => n !== num));
    }
    setSelectedOvens((prev) => prev.includes(num)
      ? prev.filter((n) => n !== num)
      : [...prev, num].sort((a, b) => a - b));
  };

  // Клик по иконке графика внутри большой карточки
  const toggleAnalytics = (num) => {
    setOpenCharts(prev => prev.includes(num) ? prev.filter(n => n !== num) : [...prev, num]);
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
    setOpenCharts((prev) => prev.filter((n) => problem.includes(n)));
  };

  const feedItems = (feed?.items ?? []).slice(0, 6);

  return (
    <div className="p-4 xl:p-6 space-y-6">
      
      {/* 1. Блок: Вся батарея (Маленькие печи) */}
      <div className="bg-white rounded-xl p-3 border border-slate-200 shadow-sm">
        <div className="flex justify-between flex-wrap gap-3 items-center mb-5">
          <h3 className="font-bold text-[15px] text-slate-800">Вся батарея · {total} печей</h3>
          <div className="flex flex-wrap gap-x-4 gap-y-1 text-[11px] font-semibold text-slate-600">
            {LEGEND.map((item) => (
              <span key={item.state} className="flex items-center gap-1.5"><div className="w-2.5 h-2.5 rounded-[2px]" style={{ backgroundColor: legendColor(item.state) }}></div>{item.label}: {counts[item.state] ?? 0}</span>
            ))}
          </div>
        </div>

        {total === 0 ? (
          <div className="text-center py-10 text-slate-400 text-sm font-medium">Загрузка данных симулятора…</div>
        ) : (
          // Оба ряда прокручиваются совместно, поэтому позиции печей совпадают с дорожками.
          <div className="overflow-x-auto pb-2">
            <div className="min-w-[740px] space-y-6">
              <div>
                <TrackWithMachines start={1} end={split} total={total} machines={machines} />
                <div className="flex justify-between w-full mt-1 px-2">
                  {ovens.slice(0, split).map((o) => (
                    <MiniOven key={o.number} oven={o} state={state} battery={battery} isSelected={selectedOvens.includes(o.number)} onClick={() => toggleOven(o.number)} />
                  ))}
                </div>
              </div>
              <div>
                <TrackWithMachines start={split + 1} end={total} total={total} machines={machines} />
                <div className="flex justify-between w-full mt-1 px-2">
                  {ovens.slice(split).map((o) => (
                    <MiniOven key={o.number} oven={o} state={state} battery={battery} isSelected={selectedOvens.includes(o.number)} onClick={() => toggleOven(o.number)} />
                  ))}
                </div>
              </div>
            </div>
          </div>
        )}
      </div>

      {/* 2. Блок: Большие карточки выбранных печей */}
      <div className="bg-white rounded-xl p-3 border border-slate-200 shadow-sm">
        <div className="flex justify-between items-center mb-5">
          <div className="flex items-center gap-2 flex-wrap">
            <h3 className="font-bold text-[15px] text-slate-800">Печи · ход процесса и состояние</h3>
            <span className="text-[10px] text-blue-700 bg-blue-50 rounded px-1.5 py-0.5 font-semibold" title="Отдельная версия компонента карточек">Карточки LIVE V3.2</span>
          </div>
          <div className="flex items-center gap-4 text-[11px]">
            <span className="text-slate-500 font-medium">включено {selectedOvens.length} из {total}</span>
            <button onClick={autoSelect} className="text-blue-600 border border-blue-200 bg-blue-50 hover:bg-blue-100 transition-colors px-3 py-1.5 rounded-md font-bold">Авто: самые проблемные</button>
          </div>
        </div>
        
        {selectedOvens.length > 0 ? (
          <div className="grid grid-cols-2 xl:grid-cols-3 2xl:grid-cols-5 gap-4">
            {selectedOvens.filter((num) => ovenByNum[num]).map(num => (
              <BigOvenCard 
                key={num} 
                num={num} 
                oven={ovenByNum[num]}
                state={state}
                battery={battery}
                onAnalyticsToggle={toggleAnalytics} 
                isAnalyticsOpen={openCharts.includes(num)} 
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
      {openCharts.length > 0 && (
        <div className="bg-slate-50 border border-slate-200 rounded-lg p-5 transition-all">
          <div className="flex justify-between items-center mb-4">
            <h3 className="font-bold text-[15px]">Аналитика печей - сравнение (открыто {openCharts.length})</h3>
          </div>
          <div className="grid grid-cols-1 xl:grid-cols-2 gap-4">
            {openCharts.filter((num) => ovenByNum[num]).map(num => <AnalyticsChartCard key={num} num={num} oven={ovenByNum[num]} onClose={() => toggleAnalytics(num)} />)}
          </div>
        </div>
      )}

      {/* 4. Блок: Лента событий */}
      <div className="bg-white rounded-xl p-5 border border-slate-200 shadow-sm">
        <h3 className="font-bold text-[15px] text-slate-800 mb-5">Ближайшие события</h3>
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


// Трасса: отдельная дорожка для каждой машины — подписи не перекрываются.
// Вдоль каждого ряда центры печей находятся от 17px до (100% - 17px):
// это 8px padding + 9px половина мини-печи. Точка остаётся точно над печью,
// а надпись дополнительно ограничена реальными 55px до края контейнера.
const TrackWithMachines = ({ start, end, total, machines }) => {
  const pusher = machines.find((m) => m.type === 'pusher');
  const quench = machines.find((m) => m.type === 'quenchCar');

  return (
    <div className="space-y-1">
      <MachineLane machine={pusher} start={start} end={end} total={total}
        label="Выталкиватель" color="#EF4444" />
      <MachineLane machine={quench} start={start} end={end} total={total}
        label="Тушил. вагон" color="#059669" />
    </div>
  );
};

const MachineLane = ({ machine, start, end, total, label, color }) => {
  const position = Number(machine?.pos);
  const valid = Number.isFinite(position) && machine != null;
  // При первой загрузке маркер монтируется сразу на правильной позиции,
  // а не едет из левого края как будто это движение машины.
  if (!valid) return <div className="relative h-9 border-b-2 border-dashed border-slate-200 rounded-t bg-slate-50/50" />;
  const atTower = machine?.type === 'quenchCar' && machine?.location === 'quenchTower';
  const towerLane = start > total / 2;
  const inRow = valid && (atTower
    ? towerLane
    : position >= start - 0.5 && position <= end + 0.5 && position >= 1 && position <= total);
  const fraction = atTower && towerLane ? 1
    : Math.max(0, Math.min(1, (valid ? position - start : 0) / Math.max(1, end - start)));
  const pointLeft = `calc(${(fraction * 100).toFixed(3)}% + ${(17 - fraction * 34).toFixed(3)}px)`;
  const labelLeft = `clamp(55px, ${pointLeft}, calc(100% - 55px))`;
  const shownLabel = atTower ? `${label} · башня` : label;

  return (
    <div className="relative h-9 border-b-2 border-dashed rounded-t bg-slate-50/50"
      style={{ borderColor: `${color}44` }} aria-label={`${label}: ${atTower ? 'у башни' : `позиция ${valid ? position.toFixed(1) : 'неизвестна'}`}`}>
      <span className="absolute bottom-3 text-[10px] font-bold whitespace-nowrap rounded border bg-white px-1.5 py-0.5 shadow-sm motion-reduce:transition-none"
        style={{ left: labelLeft, transform: 'translateX(-50%)', color, borderColor: color,
          opacity: inRow ? 1 : 0, transition: 'left 900ms linear, opacity 200ms ease', pointerEvents: 'none' }}>
        {shownLabel}
      </span>
      <span className="absolute bottom-0 w-[9px] h-[9px] rounded-full border-2 border-white shadow-sm motion-reduce:transition-none"
        style={{ left: pointLeft, transform: 'translateX(-50%)', backgroundColor: color,
          opacity: inRow ? 1 : 0, transition: 'left 900ms linear, opacity 200ms ease' }} />
    </div>
  );
};

// Мини-печь перерисовывается при новых значениях прогресса от API.
const MiniOven = ({ oven, state, battery, isSelected, onClick }) => {
  const num = oven.number;
  // Прогресс и состояние берутся из одного текущего снимка.
  const data = ovenView(oven, state, battery);
  const displayPc = data.pc;

  return (
    <button type="button" className="flex flex-col items-center group relative cursor-pointer w-[18px] shrink-0" onClick={onClick} title={`Печь П-${num}: ${data.stat}, ${displayPc.toFixed(1)} %`}>
      <div className={`
        w-[18px] h-[22px] rounded-t-[3px] transition-all border-2 flex items-end overflow-hidden bg-slate-100
        ${isSelected ? 'border-blue-600 scale-125 z-10' : 'border-slate-300 group-hover:border-slate-500'}
      `}>
        <div className="w-full transition-all duration-500" style={{ 
          height: `${displayPc}%`, backgroundColor: data.color, 
          backgroundImage: 'repeating-linear-gradient(90deg, transparent, transparent 2px, rgba(0,0,0,0.1) 2px, rgba(0,0,0,0.1) 3px)' 
        }}></div>
      </div>
      <span className={`text-[9px] mt-1.5 transition-colors ${isSelected ? 'text-slate-800 font-bold' : 'text-slate-400 font-medium group-hover:text-slate-600'}`}>{num}</span>
    </button>
  );
};

// Исправленная большая карточка
const BigOvenCard = ({ num, oven, state, battery, onAnalyticsToggle, isAnalyticsOpen }) => {
  const t = ovenView(oven, state, battery);

  // --- ВЫЧИСЛЕНИЕ ВРЕМЕНИ ДО ОКОНЧАНИЯ ---
  let remainingText = null;
  if (oven.state === 'coking' && state?.simTime && oven.readyAt) {
    const diffMs = new Date(oven.readyAt).getTime() - new Date(state.simTime).getTime();
    const diffMin = Math.round(diffMs / 60000);
    remainingText = diffMin > 0 ? `через ${diffMin} мин` : 'почти готово';
  } else if (oven.state === 'ready') {
    remainingText = 'готово';
  }

  // То же значение применяется и к числу, и к высоте заполнения.
  const displayPc = t.pc;

  return (
    <div data-oven-card={num} data-oven-live-version="V3.2" data-sim-time={state?.simTime ?? ""} data-progress={displayPc.toFixed(1)} className={`bg-white rounded-xl p-4 border-2 flex flex-col items-center relative transition-all ${isAnalyticsOpen ? 'shadow-md border-blue-200' : t.border}`}>
      <div className="w-full flex justify-between items-start mb-6">
        <span className="font-bold text-[13px] text-slate-800">Печь П- {num}</span>
        
        <div className="flex items-center gap-2">
          <button onClick={() => onAnalyticsToggle(num)} className={`w-7 h-7 rounded border flex items-center justify-center transition-colors ${isAnalyticsOpen ? 'bg-slate-100 border-slate-300 text-slate-700' : 'bg-white border-slate-200 text-slate-400 hover:text-slate-600 hover:border-slate-300'}`} title="Показать график">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"></polyline></svg>
          </button>
          <div className="w-2.5 h-2.5 rounded-full" style={{ backgroundColor: t.color }}></div>
        </div>
      </div>
      
      <div className="flex flex-col items-center mb-5">
        <div className="flex justify-center gap-1 z-10 relative -mb-[2px]">
          <div className="w-4 h-2.5 bg-white border-2 border-slate-300 border-b-0 rounded-t-[2px]"></div>
          <div className="w-4 h-2.5 bg-white border-2 border-slate-300 border-b-0 rounded-t-[2px]"></div>
          <div className="w-4 h-2.5 bg-white border-2 border-slate-300 border-b-0 rounded-t-[2px]"></div>
        </div>
        <div className="w-[70px] h-[80px] border-2 border-slate-300 rounded-[6px] relative overflow-hidden bg-white flex items-end">
          {/* АНИМАЦИЯ */}
          <div className="w-full transition-all duration-1000" style={{ height: `${displayPc}%`, backgroundColor: t.color, backgroundImage: 'repeating-linear-gradient(90deg, transparent, transparent 5px, rgba(0,0,0,0.15) 5px, rgba(0,0,0,0.15) 7px)' }}></div>
        </div>
      </div>
      
      {/* ЦИФРЫ */}
      <span className={`text-[24px] font-black ${t.txt} leading-none mb-1.5`} title={`API: ${oven.progressPct}%; снимок: ${state?.simTime ?? "нет времени"}`}>{displayPc.toFixed(1)} %</span>
      <span className="text-[11px] text-slate-500 font-medium mb-1 text-center">{t.stat}</span>
      
      {/* ВЫВОД ВРЕМЕНИ */}
      {remainingText && (
        <span className="text-[10px] font-bold text-slate-600 bg-slate-100 px-2 py-0.5 rounded mt-0.5 mb-1">
          {remainingText}
        </span>
      )}

      {oven.deviationMin > 0 && (
        <span className="text-[10px] font-bold text-[#F97316]">+{Math.round(oven.deviationMin)} мин</span>
      )}
    </div>
  );
};

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

  const data = (readings ?? [])
    .filter((r) => Number.isFinite(Number(r.temperature)))
    .map((r) => ({ time: String(r.recordedAt).slice(11, 16), val: Number(r.temperature) }));

  return (
    <div className="bg-white border border-slate-200 rounded p-4 relative shadow-sm">
      <div className="flex justify-between items-center mb-2">
        <div className="font-bold text-[13px]">Печь П-{num} · Батарея №1</div>
        <div className="flex gap-2 items-center">
          <span className={`text-[10px] px-2 py-0.5 rounded-full font-bold bg-opacity-10 ${t.txt} ${t.border} border`}>{t.stat}</span>
          <button onClick={onClose} className="text-slate-400 hover:text-red-500 font-bold p-1">✕</button>
        </div>
      </div>
      <div className="text-[10px] text-slate-400 mb-4">Температура, °C · тестовые данные из БД (не live)</div>
      {data.length > 0 ? <div className="h-24 w-full mb-4">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={data} margin={{ top: 5, right: 10, left: -25, bottom: 0 }}>
            <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#E2E8F0" />
            <XAxis dataKey="time" axisLine={false} tickLine={false} tick={{fontSize: 9, fill: '#94A3B8'}} />
            <YAxis domain={['auto', 'auto']} axisLine={false} tickLine={false} tick={{fontSize: 9, fill: '#94A3B8'}} />
            <Line type="monotone" dataKey="val" stroke="#0EA5E9" strokeWidth={2} dot={{r:3, fill: '#0EA5E9', strokeWidth: 0}} />
          </LineChart>
        </ResponsiveContainer>
      </div> : <div className="h-24 flex items-center justify-center text-[12px] text-slate-400">{readings === null ? 'Загрузка измерений…' : 'Измерений нет'}</div>}
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