import { useState } from 'react';
import { configure, control, forceFailure } from '../api/client';

// Общая обертка для затемнения фона
const ModalOverlay = ({ children, onClose }) => (
  <div className="fixed inset-0 bg-slate-900/20 backdrop-blur-[1px] z-50 flex items-center justify-center p-4" onClick={onClose}>
    <div onClick={e => e.stopPropagation()}>{children}</div>
  </div>
);

export const AlgorithmModal = ({ onClose, analytics }) => (
  <ModalOverlay onClose={onClose}>
    <div className="bg-white rounded-2xl shadow-xl w-[550px] max-w-[95vw] relative p-7">
      <button onClick={onClose} className="absolute top-4 right-4 text-slate-500 hover:text-slate-800" aria-label="Закрыть">✕</button>
      <h2 className="text-[17px] font-bold text-slate-800 mb-3">Сравнение алгоритмов</h2>
      <p className="text-[12px] text-slate-600 mb-5">
        Пока работает базовый сценарий с порядком серийности. Для сравнения B2 и B3
        необходимы их реальные расчёты на одинаковом наборе данных — вымышленные
        показатели не выводятся.
      </p>
      <table className="w-full text-[12px] text-left">
        <thead className="text-slate-500 border-b border-slate-200">
          <tr><th className="py-2">Показатель</th><th>Базовый сценарий</th><th>B2 / B3</th></tr>
        </thead>
        <tbody className="text-slate-700">
          <tr className="border-b border-slate-100"><td className="py-3">Фактических выдач</td><td>{analytics?.totalOperations ?? '—'}</td><td>Не реализованы</td></tr>
          <tr className="border-b border-slate-100"><td className="py-3">Выдач с задержкой сверх допуска</td><td>{analytics?.delayedOperations ?? '—'}</td><td>—</td></tr>
          <tr className="border-b border-slate-100"><td className="py-3">Средняя задержка</td><td>{analytics?.avgDelayMin == null ? '—' : `${analytics.avgDelayMin} мин`}</td><td>—</td></tr>
          <tr><td className="py-3">Средний интервал выдачи</td><td>{analytics?.avgIntervalMin == null ? '—' : `${analytics.avgIntervalMin} мин`}</td><td>—</td></tr>
        </tbody>
      </table>
      <div className="text-[11px] text-slate-400 mt-4">Данные базового сценария — из фактических операций симулятора; результаты B2/B3 нельзя подтвердить до реализации.</div>
    </div>
  </ModalOverlay>
);

export const SimulatorModal = ({ onClose, state, onState }) => {
  // Ползунки (0..100) <-> параметры симулятора:
  //  ускорение:  x6 ... x600 (середина = x60, то есть 1 с = 1 мин)
  //  вероятность отказа выталкивания: 0 ... 2 %
  //  частота отказов обогрева: 0 ... 0.004 на печь в час
  const speedToSlider = (f) => Math.round(50 + 50 * Math.log10((f || 60) / 60));
  const sliderToSpeed = (v) => Math.max(1, Math.round(60 * Math.pow(10, (v - 50) / 50)));
  const probToSlider = (p) => Math.round(((p ?? 0.004) / 0.02) * 100);
  const sliderToProb = (v) => v / 5000;
  const freqToSlider = (r) => Math.round(((r ?? 0.0008) / 0.004) * 100);
  const sliderToFreq = (v) => v / 25000;

  // Состояния для интерактивных ползунков (стартуют с текущих значений бэкенда)
  const [speed, setSpeed] = useState(() => speedToSlider(state?.speedFactor));
  const [freq, setFreq] = useState(() => freqToSlider(state?.ovenFaultPerHour));
  const [prob, setProb] = useState(() => probToSlider(state?.pushJamProb));
  const [seed, setSeed] = useState(String(state?.seed ?? 1042));
  const [note, setNote] = useState('');

  const ovenCount = 69;
  const minPerSec = Number((sliderToSpeed(speed) / 60).toFixed(1));
  const probText = `${(Number(prob) / 50).toFixed(1)} %`;
  const freqText = `~${(sliderToFreq(Number(freq)) * ovenCount * 24).toFixed(1)} / сутки`;

  // Отправка параметров на бэкенд. Скорость меняется на лету, а вероятность,
  // частота и seed пересоздают симулятор (время начинается заново).
  const apply = async (body, okText) => {
    try {
      onState(await configure(body));
      setNote(okText);
    } catch (e) {
      setNote(`Ошибка: ${e.message}`);
    }
  };

  const generateFailure = async () => {
    try {
      await forceFailure({ type: 'pushJam' });
      setNote('Отказ выталкивателя сработает на ближайшей выдаче');
    } catch (e) {
      setNote(`Ошибка: ${e.message}`);
    }
  };

  const resetSimulation = async () => {
    try {
      onState(await control('reset'));
      setNote('Симуляция сброшена на начало смены');
    } catch (e) {
      setNote(`Ошибка: ${e.message}`);
    }
  };

  return (
    <ModalOverlay onClose={onClose}>
      <div className="bg-white rounded-2xl shadow-xl w-[550px] p-8 relative">
        <button onClick={onClose} className="absolute right-4 top-4 text-slate-400 hover:text-slate-700 transition-colors p-2">✕</button>
        
        <div className="flex justify-between items-end mb-8">
          <h2 className="text-[17px] font-bold text-slate-800 leading-none">Симулятор</h2>
          <span className="text-[10px] text-slate-400 font-medium">{state ? (state.running ? 'идёт' : 'на паузе') : 'нет связи с бэкендом'}</span>
        </div>

        <div className="space-y-6 mb-8">
          <CustomSlider label="Ускорение времени" value={speed} setValue={setSpeed} valueText={`1 с = ${minPerSec} мин`}
            onCommit={(v) => apply({ speedFactor: sliderToSpeed(Number(v)) }, `Ускорение x${sliderToSpeed(Number(v))}`)} />
          <CustomSlider label="Частота новых событий" value={freq} setValue={setFreq} valueText={freqText}
            onCommit={(v) => apply({ ovenFaultPerHour: sliderToFreq(Number(v)) }, 'Частота отказов изменена, симуляция перезапущена')} />
          <CustomSlider label="Вероятность отказа" value={prob} setValue={setProb} valueText={probText}
            onCommit={(v) => apply({ pushJamProb: sliderToProb(Number(v)) }, 'Вероятность отказа изменена, симуляция перезапущена')} />
          
          <div className="flex items-center gap-6 mt-2">
            <span className="text-[13px] text-slate-500 w-[140px]">Seed</span>
            <input type="text" value={seed} onChange={(e) => setSeed(e.target.value.replace(/\D/g, ''))}
              onBlur={() => seed !== '' && String(state?.seed) !== seed && apply({ seed: Number(seed) }, 'Seed изменён, симуляция перезапущена')}
              className="border border-slate-300 rounded-lg py-2 px-3 w-[120px] text-[13px] text-slate-800 focus:outline-none focus:border-blue-500 hover:border-slate-400 transition-colors" />
          </div>
          <div className="text-[10px] text-slate-400">Изменение частоты, вероятности и seed перезапускает симуляцию с начала смены.</div>
        </div>

        <div className="flex gap-4">
          <button onClick={generateFailure} className="flex-1 bg-white border-2 border-red-400 text-red-500 rounded-lg py-3 text-[13px] font-bold hover:bg-red-50 active:bg-red-100 transition-colors shadow-sm">
            Сгенерировать отказ
          </button>
          <button onClick={resetSimulation} className="flex-1 bg-white border border-slate-300 text-slate-700 rounded-lg py-3 text-[13px] font-bold hover:bg-slate-50 active:bg-slate-100 transition-colors shadow-sm">
            Сбросить
          </button>
        </div>
        {note && <div className="text-[11px] text-slate-500 mt-4">{note}</div>}
      </div>
    </ModalOverlay>
  );
};

// Компонент кастомного синего ползунка
// onCommit вызывается, когда пользователь отпустил ползунок (чтобы не слать запрос на каждое движение)
const CustomSlider = ({ label, value, setValue, valueText, onCommit }) => {
  const commit = (e) => onCommit && onCommit(e.target.value);
  return (
    <div>
      <div className="flex justify-between items-center mb-3">
        <label className="text-[13px] text-slate-500">{label}</label>
        <span className="text-[12px] font-bold text-slate-800">{valueText}</span>
      </div>
      <div className="relative h-2.5 bg-slate-100 rounded-full flex items-center cursor-pointer group">
        <div className="absolute h-full bg-[#2563EB] rounded-full transition-all duration-100" style={{ width: `${value}%` }}></div>
        <input 
          type="range" min="0" max="100" value={value} onChange={(e) => setValue(e.target.value)}
          onMouseUp={commit} onTouchEnd={commit} onKeyUp={commit}
          className="absolute w-full opacity-0 cursor-pointer h-full z-20"
        />
        {/* Круглый белый ползунок с синей обводкой */}
        <div 
          className="absolute w-5 h-5 bg-white border-[3px] border-[#2563EB] rounded-full shadow-md z-10 transition-all duration-100 group-hover:scale-110 group-active:scale-95"
          style={{ left: `calc(${value}% - 10px)` }}
        ></div>
      </div>
    </div>
  );
};
