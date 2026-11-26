import { useEffect, useRef } from 'react';
import ProductionAnalytics from './ProductionAnalytics';
import { useState } from 'react';

// Аналитика — по запросу оператора, не перекрывает основной рабочий экран постоянно.
export default function AnalyticsModal({ onClose, analytics, state, battery }) {
  const closeButton = useRef(null);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [ovenNumber, setOvenNumber] = useState('');
  const exportUrl = (format) => {
    const params = new URLSearchParams();
    if (from) params.set('from', `${from}T00:00:00`);
    if (to) params.set('to', `${to}T23:59:59`);
    if (ovenNumber.trim()) params.set('ovenNumber', ovenNumber.trim());
    return `/api/temperature/export/${format}?${params.toString()}`;
  };

  useEffect(() => {
    const previouslyFocused = document.activeElement;
    closeButton.current?.focus();
    return () => previouslyFocused?.focus?.();
  }, []);

  useEffect(() => {
    const onKey = (event) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  return (
    <div role="presentation" className="fixed inset-0 z-[100] bg-slate-950/60 flex items-center justify-center p-3 sm:p-6"
      onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
      <section role="dialog" aria-modal="true" aria-labelledby="analytics-modal-title"
        className="w-full max-w-[1200px] max-h-[92vh] flex flex-col rounded-xl overflow-hidden bg-slate-100 shadow-2xl border border-slate-200">
        <header className="flex-shrink-0 bg-white border-b border-slate-200 px-4 sm:px-6 py-3 flex items-center justify-between gap-4">
          <div>
            <h2 id="analytics-modal-title" className="text-base font-bold text-slate-800">Аналитика производственного графика</h2>
            <p className="text-[11px] text-slate-500 mt-0.5">Показатели текущего прогона симулятора · план / факт / отклонения</p>
          </div>
          <button ref={closeButton} type="button" onClick={onClose}
            className="shrink-0 h-9 px-3 rounded-md border border-slate-200 bg-white text-slate-700 hover:bg-slate-50 flex items-center gap-2 font-semibold text-xs"
            aria-label="Закрыть аналитику">
            <span aria-hidden="true" className="text-base leading-none">×</span> Закрыть
          </button>
        </header>
        <div className="overflow-y-auto p-3 sm:p-5 min-h-0">
          <ProductionAnalytics analytics={analytics} state={state} battery={battery} />
          <section className="mt-5 bg-white border border-slate-200 rounded-xl p-4">
            <div className="flex flex-wrap justify-between gap-2 items-start"><div><h3 className="font-bold text-[15px] text-slate-800">Экспорт аналитики печей</h3><p className="text-[11px] text-slate-500 mt-1">Выгрузка температурных измерений из БД. Данные не являются live-телеметрией.</p></div><span className="text-[10px] text-slate-500 bg-slate-100 rounded-full px-3 py-1">CSV / Excel</span></div>
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 mt-4">
              <label className="text-xs text-slate-600">С даты<input type="date" value={from} onChange={e=>setFrom(e.target.value)} className="block w-full mt-1 border rounded-md p-2 text-sm" /></label>
              <label className="text-xs text-slate-600">По дату<input type="date" value={to} onChange={e=>setTo(e.target.value)} min={from || undefined} className="block w-full mt-1 border rounded-md p-2 text-sm" /></label>
              <label className="text-xs text-slate-600">Номер печи (необязательно)<input type="number" min="1" value={ovenNumber} onChange={e=>setOvenNumber(e.target.value)} placeholder="Все печи" className="block w-full mt-1 border rounded-md p-2 text-sm" /></label>
            </div>
            {from && to && to < from && <p className="text-xs text-red-600 mt-2">Конечная дата не может быть раньше начальной.</p>}
            <div className="flex flex-wrap gap-2 mt-4">
              <a href={exportUrl('csv')} onClick={e=>{if(from&&to&&to<from)e.preventDefault();}} className={`px-4 py-2 rounded-md text-xs font-bold border ${from&&to&&to<from?'opacity-40 pointer-events-none':'border-blue-200 bg-blue-50 text-blue-700 hover:bg-blue-100'}`}>Скачать CSV</a>
              <a href={exportUrl('xlsx')} onClick={e=>{if(from&&to&&to<from)e.preventDefault();}} className={`px-4 py-2 rounded-md text-xs font-bold border ${from&&to&&to<from?'opacity-40 pointer-events-none':'border-emerald-200 bg-emerald-50 text-emerald-700 hover:bg-emerald-100'}`}>Скачать Excel (.xlsx)</a>
              <button type="button" onClick={()=>{setFrom('');setTo('');setOvenNumber('');}} className="px-3 py-2 text-xs text-slate-500 hover:text-slate-800">Сбросить фильтры</button>
            </div>
          </section>
        </div>
      </section>
    </div>
  );
}
