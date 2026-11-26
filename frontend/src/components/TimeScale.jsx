import { useCallback, useEffect, useMemo, useState } from 'react';
import { getActiveRun, getRunOperations, manualEditOperation } from '../api/client';

const PLANT_OFFSET_MS = 7 * 3600 * 1000;
const STATUS = {
  PLANNED: { label: 'Запланировано', color: '#94A3B8' },
  IN_PROGRESS: { label: 'Выполняется', color: '#2563EB' },
  DONE: { label: 'Выполнено', color: '#10B981' },
  OVERDUE: { label: 'Просрочено', color: '#EAB308' },
  IMPOSSIBLE: { label: 'Невозможно', color: '#F97316' },
};
const plantDate = (iso) => {
  const d = new Date(Date.parse(iso ?? '') + PLANT_OFFSET_MS);
  return Number.isFinite(d.getTime()) ? `${d.getUTCFullYear()}-${String(d.getUTCMonth()+1).padStart(2,'0')}-${String(d.getUTCDate()).padStart(2,'0')}` : '';
};
// plannedStart — LocalDateTime по часам предприятия, без часового пояса.
const operationDate = (iso) => String(iso ?? '').slice(0, 10);
const dayMs = (date) => Date.parse(`${date}T00:00:00Z`);
const addDays = (date, amount) => new Date(dayMs(date) + amount * 86400000).toISOString().slice(0,10);
const formatTime = (iso) => String(iso ?? '').slice(11,16);

export default function TimeScale({ simTime }) {
  const [ops, setOps] = useState([]);
  const [runInfo, setRunInfo] = useState(null);
  const [isBoardOpen, setBoardOpen] = useState(false);
  const [editing, setEditing] = useState(null);
  const [error, setError] = useState('');
  const refresh = useCallback(() => {
    getActiveRun().then((run) => {
      if (!run?.id) { setRunInfo(null); setOps([]); return; }
      setRunInfo(run);
      return getRunOperations(run.id).then(setOps);
    }).catch(() => {});
  }, []);
  useEffect(() => { refresh(); const timer = setInterval(refresh, 5000); return () => clearInterval(timer); }, [refresh]);

  const firstDate = ops.length ? operationDate(ops[0].plannedStart) : plantDate(simTime);
  const [selectedDate, setSelectedDate] = useState('');
  useEffect(() => { if (!selectedDate && firstDate) setSelectedDate(firstDate); }, [firstDate, selectedDate]);
  const currentDate = selectedDate || firstDate;
  const dayOps = useMemo(() => ops.filter(op => operationDate(op.plannedStart) === currentDate)
    .sort((a,b) => String(a.plannedStart).localeCompare(String(b.plannedStart))), [ops,currentDate]);

  return <>
    <div className="bg-white rounded-xl p-4 border border-slate-200 shadow-sm">
      <div className="flex justify-between items-center flex-wrap gap-3">
        <div>
          <h3 className="font-bold text-[15px] text-slate-800">Шкала времени · график выдачи</h3>
          <p className="text-[11px] text-slate-500 mt-1">{runInfo ? `Прогон ${runInfo.id.slice(0,8)} · ${runInfo.algorithmTitle} · схема ${runInfo.seriality}` : 'График ещё не сформирован'}</p>
        </div>
        <div className="flex items-center gap-2">
          <span className="text-xs text-slate-500">Операций: {ops.length}</span>
          <button type="button" onClick={() => setBoardOpen(true)} disabled={!ops.length}
            className="px-4 py-2 rounded-md bg-blue-600 text-white text-xs font-bold hover:bg-blue-700 disabled:opacity-40">
            Открыть полный график
          </button>
        </div>
      </div>
      {ops.length > 0 && <div className="mt-3 flex flex-wrap gap-3 text-[10px] text-slate-500">
        {Object.entries(STATUS).map(([key, meta]) => <span key={key} className="inline-flex items-center gap-1"><i className="w-2 h-2 rounded-sm" style={{background:meta.color}} />{meta.label}</span>)}
      </div>}
      {error && <p className="text-xs text-slate-500 mt-2">{error}</p>}
    </div>

    {isBoardOpen && <div className="fixed inset-0 z-[100] bg-slate-950/60 flex items-center justify-center p-3 sm:p-6" onMouseDown={e => { if(e.target===e.currentTarget) setBoardOpen(false); }}>
      <section role="dialog" aria-modal="true" className="w-full max-w-[1400px] h-[92vh] bg-slate-100 rounded-xl shadow-2xl flex flex-col overflow-hidden">
        <header className="bg-white border-b border-slate-200 p-4 flex flex-wrap justify-between items-center gap-3">
          <div><h2 className="font-bold text-lg">Полный график производства</h2><p className="text-xs text-slate-500">Выберите день, прокрутите шкалу и нажмите на операцию для переноса</p></div>
          <button onClick={() => setBoardOpen(false)} className="border rounded-md px-3 py-2 text-sm">Закрыть ×</button>
        </header>
        <div className="p-4 flex flex-wrap items-center gap-2 bg-white border-b">
          <button onClick={() => setSelectedDate(addDays(currentDate,-1))} className="px-3 py-2 border rounded text-sm">← Предыдущий день</button>
          <input aria-label="Дата графика" type="date" value={currentDate} onChange={e=>setSelectedDate(e.target.value)} className="border rounded px-3 py-2 text-sm" />
          <button onClick={() => setSelectedDate(addDays(currentDate,1))} className="px-3 py-2 border rounded text-sm">Следующий день →</button>
          <button onClick={() => setSelectedDate(firstDate)} className="px-3 py-2 border rounded text-sm">К началу графика</button>
          <span className="ml-auto text-xs text-slate-500">{dayOps.length} операций за день · всего {ops.length}</span>
        </div>
        <div className="flex-1 overflow-auto p-4">
          <div className="bg-white border rounded-lg p-4 min-w-[900px]">
            <h3 className="font-semibold text-sm mb-3">Временная шкала · {currentDate}</h3>
            <div className="overflow-x-auto pb-3">
              <div className="relative min-w-[1200px] h-[150px]">
                <div className="absolute top-[66px] left-0 right-0 border-t-2 border-slate-300" />
                {Array.from({length:25},(_,hour)=><div key={hour} className="absolute top-0 h-full" style={{left:`${hour/24*100}%`}}>
                  <div className="h-[62px] border-l border-slate-200"/><div className="text-[10px] text-slate-500 -translate-x-1/2">{String(hour).padStart(2,'0')}:00</div>
                </div>)}
                {dayOps.map((op,index)=>{
                  const mins=Number(String(op.plannedStart).slice(11,13))*60+Number(String(op.plannedStart).slice(14,16));
                  const meta=STATUS[op.status]??STATUS.PLANNED;
                  const top=index%2===0?36:91;
                  return <button key={op.id} title={`Печь П-${op.ovenNumber} · ${op.operationType} · ${formatTime(op.plannedStart)} · ${meta.label}`}
                    onClick={()=>op.status!=='DONE'&&setEditing(op)} className="absolute -translate-x-1/2 flex flex-col items-center text-[10px] font-semibold hover:z-10 hover:scale-110"
                    style={{left:`${mins/1440*100}%`,top:`${top}px`}}>
                    <span className="whitespace-nowrap bg-white border rounded px-1.5 py-0.5 shadow-sm">П-{op.ovenNumber}</span>
                    <span className="w-3 h-3 rounded-sm border-2 border-white shadow" style={{background:meta.color}} />
                    <span className="whitespace-nowrap text-slate-500">{formatTime(op.plannedStart)}</span>
                  </button>;
                })}
              </div>
            </div>
            <p className="text-[11px] text-slate-400">Шкала прокручивается по горизонтали. Завершённые операции нельзя редактировать.</p>
          </div>
          <div className="bg-white border rounded-lg mt-4 overflow-hidden">
            <div className="p-3 border-b font-semibold text-sm">Операции за выбранный день</div>
            {dayOps.length ? <div className="overflow-x-auto"><table className="w-full text-xs text-left"><thead className="bg-slate-50 text-slate-500"><tr><th className="p-3">Время</th><th>Печь</th><th>Операция</th><th>Статус</th><th>Приоритет</th><th /></tr></thead><tbody>{dayOps.map(op=><tr key={op.id} className="border-t"><td className="p-3 tabular-nums">{formatTime(op.plannedStart)}</td><td>П-{op.ovenNumber}</td><td>{op.operationType}</td><td><span style={{color:(STATUS[op.status]??STATUS.PLANNED).color}}>{(STATUS[op.status]??STATUS.PLANNED).label}</span></td><td>{op.priority}</td><td className="p-2 text-right">{op.status!=='DONE'&&<button onClick={()=>setEditing(op)} className="text-blue-700 font-semibold">Перенести</button>}</td></tr>)}</tbody></table></div> : <p className="p-6 text-sm text-slate-400">На выбранную дату операций нет.</p>}
          </div>
        </div>
      </section>
    </div>}
    {editing && <ManualEditModal op={editing} onClose={()=>setEditing(null)} onDone={message=>{setError(message);setEditing(null);refresh();}} />}
  </>;
}

function ManualEditModal({op,onClose,onDone}) {
  const [time,setTime]=useState(String(op.plannedStart??'').slice(0,16));
  const [reason,setReason]=useState('');
  const [priority,setPriority]=useState(op.priority??1);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const submit=async()=>{
    if(!reason.trim()){setError('Укажите причину переноса');return;}
    setBusy(true);setError('');
    try {
      const utcMs=Date.parse(time+'Z')-PLANT_OFFSET_MS;
      await manualEditOperation(op.id,{plannedStart:new Date(utcMs).toISOString(),reason:reason.trim(),priority:Number(priority)});
      onDone(`П-${op.ovenNumber}: время обновлено, план пересчитан`);
    } catch(e){setError(e.message);setBusy(false);}
  };
  return <div className="fixed inset-0 z-[120] bg-slate-950/50 flex items-center justify-center p-4" onMouseDown={e=>{if(e.target===e.currentTarget)onClose();}}>
    <div className="bg-white rounded-xl shadow-xl w-full max-w-md p-5"><h3 className="font-bold mb-1">Перенос операции</h3><p className="text-xs text-slate-500 mb-4">Печь П-{op.ovenNumber} · {op.operationType} · текущее время {formatTime(op.plannedStart)}</p>
      <label className="block text-xs text-slate-600 mb-1">Новое плановое время</label><input type="datetime-local" value={time} onChange={e=>setTime(e.target.value)} className="w-full border rounded p-2 text-sm mb-3" />
      <label className="block text-xs text-slate-600 mb-1">Приоритет</label><select value={priority} onChange={e=>setPriority(Number(e.target.value))} className="w-full border rounded p-2 text-sm mb-3"><option value={1}>1 · Обычный</option><option value={2}>2 · Высокий</option><option value={3}>3 · Срочный</option></select>
      <label className="block text-xs text-slate-600 mb-1">Причина переноса *</label><textarea rows={2} value={reason} onChange={e=>setReason(e.target.value)} className="w-full border rounded p-2 text-sm mb-3" placeholder="Укажите причину" />
      {error&&<p className="text-xs text-red-600 mb-3">{error}</p>}<div className="flex justify-end gap-2"><button onClick={onClose} className="px-3 py-2 border rounded text-sm">Отмена</button><button disabled={busy} onClick={submit} className="px-3 py-2 bg-blue-600 text-white rounded text-sm font-semibold disabled:opacity-50">{busy?'Сохраняю…':'Сохранить перенос'}</button></div>
    </div>
  </div>;
}
