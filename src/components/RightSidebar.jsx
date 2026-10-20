import React from 'react';

export default function RightSidebar({ onClose }) {
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
            <span className="bg-slate-100 text-slate-500 text-[10px] px-2 py-0.5 rounded-full font-semibold">4 новых</span>
          </div>
          <div className="space-y-2">
            <DeviationCard type="error" title="Авария · Выталкиватель" desc="Блокировка привода: выдача П-5 невозможна" />
            <DeviationCard type="warning" title="Срыв графика · П-3" desc="Передержка кокса +25 мин" />
            <DeviationCard type="alert" title="Отклонение · П-2" desc="Прогноз +6 мин к плану" />
            <DeviationCard type="info" title="Информация · Тушильный вагон №1" desc="Переход на резервный путь №2" />
          </div>
        </div>

        {/* Секция Журнал событий */}
        <div className="bg-white border border-slate-200 rounded-xl p-4 shadow-sm">
          <div className="flex justify-between items-center mb-3">
            <h3 className="font-bold text-[13px] text-slate-800">Журнал событий</h3>
            <span className="text-slate-400 text-[10px]">найдено: 8</span>
          </div>
          <div className="flex gap-2 mb-3">
            <div className="flex-1">
              <label className="text-[10px] text-slate-400 mb-1 block">Оборудование</label>
              <select className="w-full border border-slate-200 rounded p-1.5 text-[11px] text-slate-700 bg-slate-50 cursor-pointer focus:outline-none focus:border-blue-400 hover:border-slate-300"><option>Всё оборудование</option></select>
            </div>
            <div className="w-[100px]">
              <label className="text-[10px] text-slate-400 mb-1 block">Период</label>
              <select className="w-full border border-slate-200 rounded p-1.5 text-[11px] text-slate-700 bg-slate-50 cursor-pointer focus:outline-none focus:border-blue-400 hover:border-slate-300"><option>Смена (8 ч)</option></select>
            </div>
          </div>
          
          <div className="flex flex-wrap gap-1.5 mb-3">
            <Tag color="bg-red-100 text-red-600">Авария</Tag>
            <Tag color="bg-yellow-200 text-yellow-800">Срыв графика</Tag>
            <Tag color="bg-yellow-100 text-yellow-700">Отклонение</Tag>
            <Tag color="bg-slate-100 text-slate-600">Информация</Tag>
          </div>
          <div className="flex gap-2 text-[10px] font-semibold text-blue-600 mb-4 border-b border-slate-100 pb-2">
            <button className="hover:text-blue-800">Аварии выбранной печи</button>
            <button className="hover:text-blue-800">Срывы всех печей · 4 ч</button>
            <button className="hover:text-blue-800 ml-auto">Сбросить</button>
          </div>

          <div className="space-y-3 relative before:absolute before:inset-0 before:ml-[5px] before:-translate-x-px md:before:mx-auto md:before:translate-x-0 before:h-full before:w-0.5 before:bg-slate-100">
            <TimelineItem time="11:05" color="bg-[#10B981]" text="Печь П-1: коксование завершено" />
            <TimelineItem time="10:58" color="bg-[#EAB308]" text="Печь П-3: передержка кокса +25 мин" />
            <TimelineItem time="10:52" color="bg-[#3B82F6]" text="Печь П-4: начало выгрузки" />
            <TimelineItem time="10:50" color="bg-slate-500" text="Тушильный вагон №1 → путь №2" />
            <TimelineItem time="10:35" color="bg-[#10B981]" text="Разгрузка печи П-2 завершена" />
            <TimelineItem time="10:20" color="bg-[#EF4444]" text="Выталкиватель: блокировка привода" />
            <TimelineItem time="09:30" color="bg-slate-500" text="Вагоны готовы к разгрузке" />
            <TimelineItem time="09:10" color="bg-[#3B82F6]" text="Печь П-5: загрузка шихты завершена" />
          </div>
        </div>

        {/* Секция Действия оператора */}
        <div className="bg-white border border-slate-200 rounded-xl p-4 shadow-sm">
          <h3 className="font-bold text-[13px] text-slate-800 mb-4">Действия оператора</h3>
          <div className="space-y-3">
            <OpAction time="11:05" text="Выгрузка П-3 → 12:30 (вручную)" />
            <OpAction time="10:42" text="П-5: доступность → доступна" />
            <OpAction time="10:15" text="Шаг между выдачами: 20 → 15 мин" />
            <OpAction time="10:00" text="Алгоритм → Вариант 2 (простои)" />
            <OpAction time="09:20" text="Приоритет срочных заказов: вкл." />
            <OpAction time="08:05" text="Загружены исходные данные" />
            <OpAction time="08:00" text="Запуск симулятора (1 с = 1 мин)" />
            <OpAction time="07:55" text="Открыт справочник оборудования" />
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
const Tag = ({ children, color }) => <span className={`text-[9px] px-2 py-0.5 rounded-full font-bold cursor-pointer hover:opacity-80 transition-opacity ${color}`}>{children}</span>;
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