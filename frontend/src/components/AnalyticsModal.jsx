import { useEffect, useRef } from 'react';
import ProductionAnalytics from './ProductionAnalytics';

// Аналитика — по запросу оператора, не перекрывает основной рабочий экран постоянно.
export default function AnalyticsModal({ onClose, analytics, state, battery }) {
  const closeButton = useRef(null);

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
        </div>
      </section>
    </div>
  );
}
