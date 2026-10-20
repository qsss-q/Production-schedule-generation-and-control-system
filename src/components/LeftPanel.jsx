import React, { useState } from 'react';

export default function LeftPanel() {
  const [step, setStep] = useState(15);
  const [highPriority, setHighPriority] = useState(true);

  return (
    // resize-x позволяет регулировать ширину панели мышкой
    <div className="w-[320px] min-w-[260px] max-w-[500px] resize-x overflow-y-auto overflow-x-hidden bg-white border-r border-slate-200 flex flex-col flex-shrink-0 z-10 shadow-[4px_0_15px_-3px_rgba(0,0,0,0.05)] h-full relative">
      <div className="p-5 flex-1">
        <h2 className="text-lg font-bold mb-6 text-slate-800">Управление графиком выдачи</h2>
        
        <div className="mb-5">
          <label className="block text-[11px] text-slate-500 mb-1.5 font-medium">Выбор алгоритма</label>
          <select className="w-full border border-slate-300 rounded-md p-2 text-[13px] bg-white focus:outline-none focus:border-blue-500 text-slate-700 cursor-pointer hover:border-slate-400 transition-colors">
            <option>Вариант 2 · Оптимизация простоев</option>
          </select>
        </div>

        <div className="mb-5">
          <label className="block text-[11px] text-slate-500 mb-1.5 font-medium">Допустимый шаг между выдачами, мин</label>
          <div className="flex items-center gap-2">
            <button onClick={() => setStep(s => Math.max(0, s - 1))} className="w-8 h-8 border border-slate-300 rounded-md bg-white flex items-center justify-center hover:bg-slate-50 active:bg-slate-100 transition-colors text-slate-600 font-medium">-</button>
            <input type="text" value={step} readOnly className="w-16 h-8 text-center border border-slate-300 rounded-md text-[13px] focus:outline-none text-slate-700 font-medium" />
            <button onClick={() => setStep(s => s + 1)} className="w-8 h-8 border border-slate-300 rounded-md bg-white flex items-center justify-center hover:bg-slate-50 active:bg-slate-100 transition-colors text-slate-600 font-medium">+</button>
          </div>
        </div>

        <div className="mb-6 flex items-center justify-between">
          <div>
            <label className="block text-[11px] text-slate-500 mb-1.5 font-medium">Приоритет срочных заказов</label>
            <div className="flex items-center gap-2">
              <div onClick={() => setHighPriority(!highPriority)} className={`w-11 h-6 rounded-full relative cursor-pointer transition-colors duration-300 ${highPriority ? 'bg-blue-600' : 'bg-slate-300'}`}>
                <div className={`w-4 h-4 bg-white rounded-full absolute top-1 transition-transform duration-300 shadow-sm ${highPriority ? 'translate-x-6' : 'translate-x-1'}`}></div>
              </div>
              <span className="text-[13px] font-medium text-slate-700 flex items-center gap-1 cursor-pointer" onClick={() => setHighPriority(!highPriority)}>
                Высокий <span className="text-[10px] text-slate-400">▼</span>
              </span>
            </div>
          </div>
          <span className="text-[11px] text-slate-400 self-end mb-0.5">заказы №17, №21</span>
        </div>

        <div className="mb-6 space-y-3">
          <input type="text" defaultValue="Окно смены 08:00 – 20:00" className="w-full border border-slate-300 rounded-md p-2 text-[13px] text-slate-700 bg-white hover:border-slate-400 transition-colors focus:outline-none" />
          <input type="text" defaultValue="Макс. простой до срыва 30 мин" className="w-full border border-slate-300 rounded-md p-2 text-[13px] text-slate-700 bg-white hover:border-slate-400 transition-colors focus:outline-none" />
        </div>

        <div className="mb-6">
          <label className="block text-[11px] text-slate-500 mb-2 font-medium">Доступность оборудования</label>
          <div className="grid grid-cols-5 gap-1.5 mb-2">
            {['П-1', 'П-2', 'П-3', 'П-4', 'П-5'].map(p => (
              <button key={p} className="border border-[#10B981]/30 bg-[#10B981]/10 text-[#059669] rounded hover:bg-[#10B981]/20 transition-colors text-[11px] py-1.5 font-semibold flex items-center justify-center gap-0.5">✓ {p}</button>
            ))}
          </div>
          <div className="grid grid-cols-2 gap-2">
            <button className="border border-red-200 bg-red-50 text-red-600 hover:bg-red-100 transition-colors rounded-md text-[11px] py-2 font-semibold flex items-center justify-center gap-1">✕ Выталкиватель</button>
            <button className="border border-[#10B981]/30 bg-[#10B981]/10 text-[#059669] hover:bg-[#10B981]/20 transition-colors rounded-md text-[11px] py-2 font-semibold flex items-center justify-center gap-1">✓ Тушил. вагон</button>
          </div>
        </div>

        <div className="space-y-2.5">
          <button className="w-full bg-[#2563EB] text-white rounded-md p-2.5 text-[13px] font-semibold shadow-sm hover:bg-blue-700 active:bg-blue-800 transition-colors">Сформировать график</button>
          <div className="flex gap-2.5">
            <button className="flex-1 border border-slate-300 bg-white rounded-md p-2 text-[13px] font-semibold text-slate-700 hover:bg-slate-50 active:bg-slate-100 transition-colors shadow-sm">Загрузить данные</button>
            <button className="flex-1 border border-slate-300 bg-white rounded-md p-2 text-[13px] font-semibold text-slate-700 hover:bg-slate-50 active:bg-slate-100 transition-colors shadow-sm">Справочник</button>
          </div>
        </div>
      </div>

      <div className="bg-slate-50/80 p-5 border-t border-slate-200">
        <div className="flex justify-between items-center mb-3">
          <h3 className="font-bold text-[13px] text-slate-800">Справочник оборудования</h3>
          <button className="text-blue-600 text-[11px] border border-blue-200 px-2.5 py-0.5 rounded-md bg-white hover:bg-blue-50 transition-colors font-medium">+ Новый</button>
        </div>
        <table className="w-full text-[11px] text-left">
          <thead className="text-slate-400 border-b border-slate-200">
            <tr><th className="font-medium pb-1.5">Объект</th><th className="font-medium pb-1.5">Тип</th><th className="font-medium pb-1.5 text-right">Доступ</th></tr>
          </thead>
          <tbody className="text-slate-600">
            {['П-1', 'П-2', 'П-3', 'П-4', 'П-5'].map(p => (
              <tr key={p} className="border-b border-slate-100 last:border-0"><td className="py-1.5 font-medium">Печь {p}</td><td>Печь</td><td className="text-right"><span className="text-[#059669] bg-[#10B981]/10 px-1.5 py-0.5 rounded font-semibold inline-block">доступен</span></td></tr>
            ))}
            <tr className="border-b border-slate-100"><td className="py-1.5 text-slate-400 font-medium">Коксовыталкиватель</td><td className="text-slate-400">Механизм</td><td className="text-right"><span className="text-red-600 bg-red-50 px-1.5 py-0.5 rounded font-semibold inline-block">недоступен</span></td></tr>
            <tr><td className="py-1.5 font-medium">Тушильный вагон</td><td>Механизм</td><td className="text-right"><span className="text-[#059669] bg-[#10B981]/10 px-1.5 py-0.5 rounded font-semibold inline-block">доступен</span></td></tr>
          </tbody>
        </table>
      </div>
    </div>
  );
}