import React, { useState } from 'react';
import LeftPanel from './components/LeftPanel';
import RightSidebar from './components/RightSidebar';
import { AlgorithmModal, SimulatorModal } from './components/Modals';
import MainWorkspace from './components/MainWorkspace';
import { useLive } from './api/useLive';
import { control } from './api/client';
import { fmtDate, fmtTime } from './utils/format';
import { recentDeviations } from './utils/eventView';

export default function App() {
  const [isRightPanelOpen, setRightPanelOpen] = useState(false);
  const [isAlgoModalOpen, setAlgoModalOpen] = useState(false);
  const [isSimModalOpen, setSimModalOpen] = useState(false);

  // Живые данные симулятора (опрос бэкенда раз в секунду)
  const { battery, state, ovens, machines, feed, events, error, applyState } = useLive();
  const [actionError, setActionError] = useState(null);

  // ПУСК / ПАУЗА / СТОП
  const act = async (action) => {
    try {
      applyState(await control(action));
      setActionError(null);
    } catch (e) {
      setActionError(e.message);
    }
  };

  const newDeviations = state ? recentDeviations(events, state.simMinutes).length : 0;
  const problem = error?.message ?? actionError;

  return (
    <div className="flex h-screen bg-[#F1F5F9] text-slate-800 font-sans overflow-hidden">
      
      {/* Левая панель (теперь поддерживает изменение ширины мышкой) */}
      <LeftPanel />

      {/* Центральная часть */}
      <div className="flex-1 flex flex-col overflow-hidden min-w-[500px]">
        <header className="h-[68px] bg-white border-b border-slate-200 flex items-center justify-between px-6 flex-shrink-0 z-10 shadow-sm">
          <h1 className="text-[16px] font-bold tracking-tight text-slate-800">{battery?.name ?? 'Коксовая батарея №1'} · Контроль графика выдачи кокса</h1>
          
          <div className="flex items-center gap-4">
            <div className="bg-slate-100 text-slate-600 px-3.5 py-1.5 rounded-full text-[11px] font-semibold">
              {state ? `${fmtDate(state.simTime)} ${fmtTime(state.simTime)} - симуляция x${state.speedFactor}` : 'нет связи с симулятором'}
            </div>
            
            <div className="flex border border-slate-200 rounded-md overflow-hidden shadow-sm h-[30px]">
              <button onClick={() => act('start')} className="bg-[#10B981] hover:bg-[#059669] text-white px-4 text-[11px] font-bold flex items-center gap-1 transition-colors">▶ ПУСК</button>
              <button onClick={() => act('pause')} className="bg-slate-50 hover:bg-slate-100 text-slate-600 px-4 text-[11px] font-bold border-l border-slate-200 transition-colors">⏸ ПАУЗА</button>
              <button onClick={() => act('reset')} className="bg-white hover:bg-red-50 text-red-500 border border-red-500 px-4 text-[11px] font-bold ml-1 rounded-md transition-colors">■ СТОП</button>
            </div>
            
            <div className="flex items-center gap-3 text-[11px] text-slate-500 ml-2 border-l border-slate-200 pl-5">
              Оператор смены 
              <div className="w-8 h-8 bg-[#DBEAFE] text-[#1D4ED8] rounded-full flex items-center justify-center font-bold border border-blue-200 shadow-sm cursor-pointer hover:bg-blue-200 transition-colors">ОП</div>
            </div>

            <div className="flex gap-2 ml-2">
              <button onClick={() => setRightPanelOpen(!isRightPanelOpen)} className={`w-8 h-8 border rounded-md flex flex-col items-center justify-center gap-0.5 transition-colors relative ${isRightPanelOpen ? 'bg-blue-50 border-blue-300' : 'bg-white border-slate-200 hover:bg-slate-50'}`}>
                <span className="w-4 h-3 border-2 border-slate-400 rounded-sm"></span><span className="text-[7px] font-bold text-slate-500">Увед.</span>
                {newDeviations > 0 && (
                  <span className="absolute -top-1.5 -right-1.5 bg-red-500 text-white text-[9px] font-bold w-4 h-4 flex items-center justify-center rounded-full shadow-sm border border-white">{newDeviations > 9 ? '9+' : newDeviations}</span>
                )}
              </button>
              <button onClick={() => setAlgoModalOpen(true)} className="w-8 h-8 border border-slate-200 bg-white hover:bg-slate-50 rounded-md flex flex-col items-center justify-center gap-0.5 transition-colors">
                <span className="text-slate-400 text-[10px]">📊</span><span className="text-[7px] font-bold text-slate-500">Алго</span>
              </button>
              <button onClick={() => setSimModalOpen(true)} className="w-8 h-8 border border-slate-200 bg-white hover:bg-slate-50 rounded-md flex flex-col items-center justify-center gap-0.5 transition-colors">
                <span className="text-slate-400 text-[10px]">▶</span><span className="text-[7px] font-bold text-slate-500">Сим</span>
              </button>
            </div>
          </div>
        </header>

        {/* Если бэкенд недоступен или действие не удалось — показываем причину, а не пустой экран */}
        {problem && (
          <div className="bg-red-50 border-b border-red-200 text-red-700 text-[12px] px-6 py-2 flex-shrink-0">
            {error ? `Нет связи с бэкендом (${problem}). Проверьте, что запущен docker compose, адрес localhost:8080.` : `Ошибка: ${problem}`}
          </div>
        )}

        <div className="flex-1 overflow-y-auto bg-slate-100">
          <MainWorkspace ovens={ovens} machines={machines} feed={feed} />
        </div>
      </div>

      {/* Правая панель теперь стоит в flex-потоке, а не абсолютно */}
      {isRightPanelOpen && <RightSidebar onClose={() => setRightPanelOpen(false)} events={events} state={state} />}
      
      {isAlgoModalOpen && <AlgorithmModal onClose={() => setAlgoModalOpen(false)} />}
      {isSimModalOpen && <SimulatorModal onClose={() => setSimModalOpen(false)} state={state} onState={applyState} />}
    </div>
  );
}
