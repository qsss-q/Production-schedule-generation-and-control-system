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
      
      {/* Левая панель */}
      <LeftPanel />

      {/* Центральная часть */}
      <div className="flex-1 flex flex-col overflow-hidden min-w-[500px]">
        <header className="h-[68px] bg-white border-b border-slate-200 flex items-center justify-between px-6 flex-shrink-0 z-10 shadow-sm">
          <h1 className="text-[16px] font-bold tracking-tight text-slate-800">{battery?.name ?? 'Коксовая батарея №1'} · Контроль графика выдачи кокса</h1>
          
          <div className="flex items-center gap-4">
            <div className="bg-slate-100 text-slate-600 px-3.5 py-1.5 rounded-full text-[11px] font-semibold">
              {state ? `${fmtDate(state.simTime)} ${fmtTime(state.simTime)} - симуляция x${state.speedFactor}` : 'нет связи с симулятором'}
            </div>
            
            {/* Обновленный блок кнопок ПУСК / ПАУЗА / СТОП с SVG */}
            <div className="flex border border-slate-200 rounded-md overflow-hidden shadow-sm h-[30px] bg-white">
              <button onClick={() => act('start')} className="hover:bg-slate-50 text-emerald-600 px-3 flex items-center justify-center transition-colors group" title="Пуск">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" className="group-active:scale-95 transition-transform">
                  <path d="M8 5v14l11-7z"/>
                </svg>
              </button>
              <div className="w-px bg-slate-200 h-full"></div>
              <button onClick={() => act('pause')} className="hover:bg-slate-50 text-amber-500 px-3 flex items-center justify-center transition-colors group" title="Пауза">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" className="group-active:scale-95 transition-transform">
                  <path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/>
                </svg>
              </button>
              <div className="w-px bg-slate-200 h-full"></div>
              <button onClick={() => act('reset')} className="hover:bg-red-50 text-red-500 px-3 flex items-center justify-center transition-colors group" title="Стоп">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor" className="group-active:scale-95 transition-transform">
                  <path d="M6 6h12v12H6z"/>
                </svg>
              </button>
            </div>
            
            <div className="flex items-center gap-3 text-[11px] text-slate-500 ml-2 border-l border-slate-200 pl-5">
              Оператор смены 
              <div className="w-8 h-8 bg-[#DBEAFE] text-[#1D4ED8] rounded-full flex items-center justify-center font-bold border border-blue-200 shadow-sm cursor-pointer hover:bg-blue-200 transition-colors">ОП</div>
            </div>

            <div className="flex gap-2 ml-2">
              <button onClick={() => setRightPanelOpen(!isRightPanelOpen)} className={`w-8 h-8 border rounded-md flex flex-col items-center justify-center gap-0.5 transition-colors relative ${isRightPanelOpen ? 'bg-blue-50 border-blue-300' : 'bg-white border-slate-200 hover:bg-slate-50'}`}>
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="text-slate-500"><path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"></path><path d="M13.73 21a2 2 0 0 1-3.46 0"></path></svg>
                <span className="text-[7px] font-bold text-slate-500 leading-none">Увед.</span>
                {newDeviations > 0 && (
                  <span className="absolute -top-1.5 -right-1.5 bg-red-500 text-white text-[9px] font-bold w-4 h-4 flex items-center justify-center rounded-full shadow-sm border border-white">{newDeviations > 9 ? '9+' : newDeviations}</span>
                )}
              </button>
              
              <button onClick={() => setAlgoModalOpen(true)} className="w-8 h-8 border border-slate-200 bg-white hover:bg-slate-50 rounded-md flex flex-col items-center justify-center gap-0.5 transition-colors group">
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="text-slate-400 group-hover:text-blue-500 transition-colors"><line x1="18" y1="20" x2="18" y2="10"></line><line x1="12" y1="20" x2="12" y2="4"></line><line x1="6" y1="20" x2="6" y2="14"></line></svg>
                <span className="text-[7px] font-bold text-slate-500 leading-none group-hover:text-blue-500 transition-colors">Алго</span>
              </button>
              
              <button onClick={() => setSimModalOpen(true)} className="w-8 h-8 border border-slate-200 bg-white hover:bg-slate-50 rounded-md flex flex-col items-center justify-center gap-0.5 transition-colors group">
                <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="text-slate-400 group-hover:text-slate-700 transition-colors"><circle cx="12" cy="12" r="3"></circle><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path></svg>
                <span className="text-[7px] font-bold text-slate-500 leading-none group-hover:text-slate-700 transition-colors">Сим</span>
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