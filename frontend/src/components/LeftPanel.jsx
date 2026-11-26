import { useEffect, useRef, useState } from 'react';
import {
  configure,
  generateSchedule,
  getActiveRun,
  getActiveSettings,
  getEquipment,
  importMachinesCsv,
  importOvensCsv,
  importStateFile,
  logOperatorAction,
  runExportCsvUrl,
  saveSettings,
  setEquipmentAvailability,
  setOvenAvailability,
  stateExportUrl,
} from '../api/client';

// Алгоритмы планирования (docs/05 §5): B1 — эталон, B2/B3 — способы погасить долг.
const ALGORITHMS = [
  { code: 'b1_fifo', label: 'B1 FIFO · эталон' },
  { code: 'b2_idle', label: 'B2 · догон долга' },
  { code: 'b3_smooth', label: 'B3 · сглаживание' },
];
const SCHEMES = ['2-1', '5-2', '9-2'];
// Машины комплекта: машинный код для /api/v1/machines/{code}/* и подпись.
const MACHINE_CHIPS = [
  { code: 'PUSHING', label: 'КВ', title: 'Коксовыталкиватель' },
  { code: 'DOOR_MACHINE', label: 'ДСМ', title: 'Двересъёмная машина' },
  { code: 'QUENCH_CAR', label: 'ТВ', title: 'Тушильный вагон' },
  { code: 'LOADING_CAR', label: 'УВ', title: 'Углезагрузочный вагон' },
];

const CHIP_OK = 'border border-[#10B981]/30 bg-[#10B981]/10 text-[#059669] hover:bg-[#10B981]/20';
const CHIP_OFF = 'border border-red-200 bg-red-50 text-red-600 hover:bg-red-100';

export default function LeftPanel({ ovens = [] }) {
  const [step, setStep] = useState(15);
  const [algorithm, setAlgorithm] = useState('b1_fifo');
  const [defaultPriority, setDefaultPriority] = useState(1);
  const [scheme, setScheme] = useState('9-2');
  const [shiftText, setShiftText] = useState('Окно смены 08:00 – 20:00');
  const [idleText, setIdleText] = useState('Порог просрочки до срыва графика 30 мин');

  // данные из БД
  const [settings, setSettings] = useState(null);
  const [equipment, setEquipment] = useState([]);
  const [dbError, setDbError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  // доступность печей по номерам: из живого API (unavailable) с локальной подсветкой
  const [unavailable, setUnavailable] = useState({});
  const fileRef = useRef(null);
  const fileKind = useRef(null);
  const stateFileRef = useRef(null);

  // Настройки и справочник оборудования берём из БД: GET /api/settings/active, GET /api/equipment
  useEffect(() => {
    let alive = true;
    getActiveSettings()
      .then((s) => {
        if (!alive) return;
        setSettings(s);
        if (s.stepMinutes != null) setStep(s.stepMinutes);
        if (s.defaultPriority != null) setDefaultPriority(s.defaultPriority);
        const mapped = { FIFO: 'b1_fifo', IDLE_OPTIMIZATION: 'b2_idle', SMOOTH: 'b3_smooth' }[s.algorithm];
        if (mapped) setAlgorithm(mapped);
        if (s.shiftStart && s.shiftEnd) {
          setShiftText(`Окно смены ${String(s.shiftStart).slice(0, 5)} – ${String(s.shiftEnd).slice(0, 5)}`);
        }
        if (s.maxIdleMinutes != null) setIdleText(`Порог просрочки до срыва графика ${s.maxIdleMinutes} мин`);
      })
      .catch((e) => alive && setDbError(e.message));
    getEquipment()
      .then((list) => alive && setEquipment(list))
      .catch((e) => alive && setDbError(e.message));
    return () => { alive = false; };
  }, []);

  const ovenItem = (num) => equipment.find((e) => e.name === `Печь П-${num}`);
  const machineItems = MACHINE_CHIPS.map((m) => ({
    ...m,
    item: equipment.find((e) => e.name.includes(m.label + '-')) ?? equipment.find((e) => e.type === m.code),
  }));

  // Переключатель доступности машины: PATCH /api/equipment/{id}/availability —
  // бэкенд применяет его и к живому прогону (печь — вывод из серии, машина — простой)
  const toggleAvailability = async (item) => {
    if (!item) return;
    try {
      const updated = await setEquipmentAvailability(item.id, !item.available);
      setEquipment((list) => list.map((e) => (e.id === updated.id ? updated : e)));
      logOperatorAction(`${item.name}: доступность → ${updated.available ? 'доступна' : 'недоступна'}`).catch(() => {});
    } catch (e) {
      setMessage(`Ошибка: ${e.message}`);
    }
  };

  // Печь из живого прогона: вывод из серии / возврат — слот пропускается, порядок тот же
  const toggleOven = async (num) => {
    const next = !(unavailable[num] ?? false);
    try {
      await setOvenAvailability(num, !next, next ? 'выведена оператором' : 'возврат в серию');
      setUnavailable((prev) => ({ ...prev, [num]: next }));
      const item = ovenItem(num);
      if (item) setEquipment((list) => list.map((e) => (e.id === item.id ? { ...e, available: !next } : e)));
      logOperatorAction(`Печь П-${num}: ${next ? 'выведена из серии' : 'возвращена в серию'}`, 'OVEN_AVAILABILITY').catch(() => {});
    } catch (e) {
      setMessage(`Ошибка: ${e.message}`);
    }
  };

  // «Сформировать график»: сохраняем настройки в БД и запускаем прогон планировщика.
  // Алгоритм и шаг реально влияют на план; схема пересобирает батарею.
  const handleGenerate = async () => {
    setBusy(true);
    setMessage('');
    try {
      const win = shiftText.match(/(\d{1,2}):(\d{2})\D+(\d{1,2}):(\d{2})/);
      const idle = idleText.match(/(\d+)/);
      if (settings) {
        const next = {
          ...settings,
          stepMinutes: step,
          algorithm,
          shiftStart: win ? `${win[1].padStart(2, '0')}:${win[2]}` : settings.shiftStart,
          shiftEnd: win ? `${win[3].padStart(2, '0')}:${win[4]}` : settings.shiftEnd,
          maxIdleMinutes: idle ? Number(idle[1]) : settings.maxIdleMinutes,
          defaultPriority,
        };
        const saved = await saveSettings(settings.id, next);
        if (saved.algorithm !== settings.algorithm) {
          await logOperatorAction(`Алгоритм планирования: ${settings.algorithm} → ${saved.algorithm}`);
        }
        if (saved.stepMinutes !== settings.stepMinutes) {
          await logOperatorAction(`Шаг между выдачами: ${settings.stepMinutes} → ${saved.stepMinutes} мин`);
        }
        setSettings(saved);
      }
      if (scheme !== undefined) {
        // серийность — параметр батареи: пересборка симулятора под выбранную схему
        await configure({ seriality: scheme });
      }
      const run = await generateSchedule();
      setMessage(`График сформирован: ${run.totalOperations} операций (${run.algorithmCode || algorithm}, схема ${scheme})`);
    } catch (e) {
      setMessage(`Ошибка: ${e.message}`);
    } finally {
      setBusy(false);
    }
  };

  // Выбор файла: печи или машины (CSV), состояние батареи (JSON)
  const onFile = async ({ target }) => {
    const file = target.files?.[0];
    target.value = '';
    if (!file) return;
    setBusy(true);
    setMessage('');
    try {
      if (fileKind.current === 'state') {
        const state = await importStateFile(file);
        setMessage(`Состояние батареи загружено: ${state.simMinutes} мин симуляции, симуляция на паузе`);
        await logOperatorAction('Загружено состояние батареи из файла', 'SIMULATOR_IMPORT_STATE').catch(() => {});
      } else {
        const result = fileKind.current === 'machines'
          ? await importMachinesCsv(file)
          : await importOvensCsv(file);
        setMessage(result.summary);
        const list = await getEquipment();
        setEquipment(list);
      }
    } catch (e) {
      setMessage(`Ошибка: ${e.message}`);
    } finally {
      setBusy(false);
      fileKind.current = null;
    }
  };

  const pickFile = (kind) => {
    fileKind.current = kind;
    (kind === 'state' ? stateFileRef : fileRef).current?.click();
  };

  // Выгрузка результатов действующего прогона: план/факт/статусы в CSV (docs/04 §8)
  const exportResults = async () => {
    try {
      const run = await getActiveRun();
      if (!run?.id) {
        setMessage('Сначала сформируйте график — выгружать пока нечего');
        return;
      }
      window.open(runExportCsvUrl(run.id, 'operations'), '_blank');
    } catch (e) {
      setMessage(`Ошибка: ${e.message}`);
    }
  };

  const tableRows = [
    ...equipment.filter((e) => e.type !== 'OVEN'),
  ];

  return (
    // resize-x позволяет регулировать ширину панели мышкой
    <div className="w-[320px] min-w-[260px] max-w-[500px] resize-x overflow-y-auto overflow-x-hidden bg-white border-r border-slate-200 flex flex-col flex-shrink-0 z-10 shadow-[4px_0_15px_-3px_rgba(0,0,0,0.05)] h-full relative">
      <input ref={fileRef} type="file" accept=".csv,text/csv" className="hidden" onChange={onFile} />
      <input ref={stateFileRef} type="file" accept=".json,application/json" className="hidden" onChange={onFile} />
      <div className="p-5 flex-1">
        <h2 className="text-lg font-bold mb-6 text-slate-800">Управление графиком выдачи</h2>
        {dbError && <div className="text-[11px] text-red-600 mb-3">Нет связи с БД: {dbError}</div>}

        <div className="mb-5">
          <label className="block text-[11px] text-slate-500 mb-1.5 font-medium">Алгоритм планирования</label>
          <select value={algorithm} onChange={(e) => setAlgorithm(e.target.value)}
            className="w-full border border-slate-300 rounded-md p-2 text-[13px] bg-white text-slate-700">
            {ALGORITHMS.map((a) => <option key={a.code} value={a.code}>{a.label}</option>)}
          </select>
        </div>

        <div className="mb-5">
          <label className="block text-[11px] text-slate-500 mb-1.5 font-medium">Схема серийности</label>
          <select value={scheme} onChange={(e) => setScheme(e.target.value)}
            className="w-full border border-slate-300 rounded-md p-2 text-[13px] bg-white text-slate-700">
            {SCHEMES.map((s) => <option key={s} value={s}>{s} {s === '9-2' ? '· базовая' : ''}</option>)}
          </select>
        </div>

        <div className="mb-5">
          <label className="block text-[11px] text-slate-500 mb-1.5 font-medium">Приоритет новых операций</label>
          <select value={defaultPriority} onChange={(e) => setDefaultPriority(Number(e.target.value))}
            className="w-full border border-slate-300 rounded-md p-2 text-[13px] bg-white text-slate-700">
            <option value={1}>1 · Обычный</option>
            <option value={2}>2 · Высокий</option>
            <option value={3}>3 · Срочный</option>
          </select>
          <div className="text-[10px] text-slate-400 mt-1">Приоритет не нарушает технологическую серийность; он уменьшает допустимое опоздание операции.</div>
        </div>

        <div className="mb-5">
          <label className="block text-[11px] text-slate-500 mb-1.5 font-medium">Допустимый шаг между выдачами, мин</label>
          <div className="flex items-center gap-2">
            <button onClick={() => setStep(s => Math.max(0, s - 1))} className="w-8 h-8 border border-slate-300 rounded-md bg-white flex items-center justify-center hover:bg-slate-50 active:bg-slate-100 transition-colors text-slate-600 font-medium">-</button>
            <input type="text" value={step} readOnly className="w-16 h-8 text-center border border-slate-300 rounded-md text-[13px] focus:outline-none text-slate-700 font-medium" />
            <button onClick={() => setStep(s => s + 1)} className="w-8 h-8 border border-slate-300 rounded-md bg-white flex items-center justify-center hover:bg-slate-50 active:bg-slate-100 transition-colors text-slate-600 font-medium">+</button>
          </div>
        </div>

        <div className="mb-6 space-y-3">
          <input type="text" value={shiftText} onChange={(e) => setShiftText(e.target.value)} className="w-full border border-slate-300 rounded-md p-2 text-[13px] text-slate-700 bg-white hover:border-slate-400 transition-colors focus:outline-none" />
          <input type="text" value={idleText} onChange={(e) => setIdleText(e.target.value)} className="w-full border border-slate-300 rounded-md p-2 text-[13px] text-slate-700 bg-white hover:border-slate-400 transition-colors focus:outline-none" />
        </div>

        <div className="mb-6">
          <label className="block text-[11px] text-slate-500 mb-2 font-medium">Доступность · печи (клик — вывести из серии)</label>
          <div className="max-h-[92px] overflow-y-auto grid grid-cols-10 gap-1 mb-3 pr-1">
            {ovens.map((o) => {
              const off = unavailable[o.number] ?? o.state === 'unavailable';
              return (
                <button key={o.number} title={`П-${o.number}: ${off ? 'вне серии' : 'в серии'}`}
                  onClick={() => toggleOven(o.number)}
                  className={`${off ? CHIP_OFF : CHIP_OK} rounded text-[9px] py-1 font-semibold`}>
                  {o.number}
                </button>
              );
            })}
            {ovens.length === 0 && Array.from({ length: 69 }, (_, i) => (
              <span key={i} className="text-[9px] text-slate-300 text-center py-1">{i + 1}</span>
            ))}
          </div>
          <label className="block text-[11px] text-slate-500 mb-2 font-medium">Доступность · машины</label>
          <div className="grid grid-cols-4 gap-1.5">
            {machineItems.map((m) => {
              const ok = m.item ? m.item.available : true;
              return (
                <button key={m.code} title={m.title} onClick={() => m.item && toggleAvailability(m.item)}
                  className={`${ok ? CHIP_OK : CHIP_OFF} rounded-md transition-colors text-[11px] py-2 font-semibold flex items-center justify-center gap-0.5`}>
                  {ok ? '✓' : '✕'} {m.label}
                </button>
              );
            })}
          </div>
        </div>

        <div className="space-y-2.5">
          <button onClick={handleGenerate} disabled={busy} className="w-full bg-[#2563EB] text-white rounded-md p-2.5 text-[13px] font-semibold shadow-sm hover:bg-blue-700 active:bg-blue-800 transition-colors disabled:opacity-60">{busy ? 'Формирую…' : 'Сформировать график'}</button>
          {message && <div className="text-[11px] text-slate-500">{message}</div>}
          <div className="flex gap-2.5">
            <button onClick={() => pickFile('ovens')} disabled={busy} className="flex-1 border border-slate-200 bg-white rounded-md p-2 text-[13px] font-semibold text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-60">Загрузить данные</button>
            <a href={stateExportUrl} title="Выгрузить состояние батареи в файл JSON"
              className="flex-1 border border-slate-200 bg-white rounded-md p-2 text-[13px] font-semibold text-slate-600 hover:bg-slate-50 transition-colors text-center">Выгрузить</a>
            <button onClick={() => pickFile('state')} disabled={busy} title="Загрузить состояние батареи из файла"
              className="flex-1 border border-slate-200 bg-white rounded-md p-2 text-[13px] font-semibold text-slate-600 hover:bg-slate-50 transition-colors disabled:opacity-60">Восстановить</button>
          </div>
          <button onClick={exportResults} className="w-full border border-slate-200 bg-white rounded-md p-2 text-[13px] font-semibold text-slate-600 hover:bg-slate-50 transition-colors">
            Выгрузка результатов (CSV)
          </button>
        </div>
      </div>

      <div className="bg-slate-50/80 p-5 border-t border-slate-200">
        <div className="flex justify-between items-center mb-3">
          <h3 className="font-bold text-[13px] text-slate-800">Справочник оборудования</h3>
          <button onClick={() => pickFile('machines')} disabled={busy} title="Импорт справочника машин из CSV (docs/04 §3)"
            className="text-blue-600 text-[11px] border border-blue-200 px-2.5 py-0.5 rounded-md bg-blue-50 hover:bg-blue-100 font-medium">Импорт CSV</button>
        </div>
        <table className="w-full text-[11px] text-left">
          <thead className="text-slate-400 border-b border-slate-200">
            <tr><th className="font-medium pb-1.5">Объект</th><th className="font-medium pb-1.5">Тип</th><th className="font-medium pb-1.5 text-right">Доступ</th></tr>
          </thead>
          <tbody className="text-slate-600">
            {tableRows.map((e) => (
              <tr key={e.id} className="border-b border-slate-100 last:border-0">
                <td className={`py-1.5 font-medium ${e.available ? '' : 'text-slate-400'}`}>{e.name}</td>
                <td className={e.available ? '' : 'text-slate-400'}>{e.type === 'OVEN' ? 'Печь' : 'Механизм'}</td>
                <td className="text-right">
                  {e.available
                    ? <span className="text-[#059669] bg-[#10B981]/10 px-1.5 py-0.5 rounded font-semibold inline-block">доступен</span>
                    : <span className="text-red-600 bg-red-50 px-1.5 py-0.5 rounded font-semibold inline-block">недоступен</span>}
                </td>
              </tr>
            ))}
            {tableRows.length === 0 && (
              <tr><td colSpan={3} className="py-2 text-slate-400">Справочник пуст или нет связи с БД</td></tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
