import { useCallback, useEffect, useRef, useState } from 'react';
import { getBattery, getEvents, getFeed, getMachines, getOvens, getState } from './client';

// Опрос бэкенда раз в секунду: время симуляции, печи, машины, лента, события.
// setTimeout вместо setInterval, чтобы запросы не накладывались друг на друга.
export function useLive(intervalMs = 1000) {
  const [data, setData] = useState({
    battery: null,
    state: null,
    ovens: [],
    machines: [],
    feed: null,
    events: [],
  });
  const [error, setError] = useState(null);
  const batteryLoaded = useRef(false);

  useEffect(() => {
    let alive = true;
    let timer;

    const tick = async () => {
      try {
        const [state, ovens, machines, feed, events] = await Promise.all([
          getState(),
          getOvens(),
          getMachines(),
          getFeed(20),
          getEvents(200),
        ]);
        if (!alive) return;
        setData((prev) => ({ ...prev, state, ovens, machines, feed, events }));
        setError(null);

        // паспорт батареи нужен один раз; если не получилось — повторим на следующем тике
        if (!batteryLoaded.current) {
          try {
            const battery = await getBattery();
            if (alive) {
              batteryLoaded.current = true;
              setData((prev) => ({ ...prev, battery }));
            }
          } catch {
            // не критично
          }
        }
      } catch (e) {
        if (alive) setError(e);
      } finally {
        if (alive) timer = setTimeout(tick, intervalMs);
      }
    };

    tick();
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, [intervalMs]);

  // после нажатия ПУСК/ПАУЗА сразу показываем ответ сервера, не дожидаясь следующего опроса
  const applyState = useCallback((state) => {
    setData((prev) => ({ ...prev, state }));
  }, []);

  return { ...data, error, applyState };
}
