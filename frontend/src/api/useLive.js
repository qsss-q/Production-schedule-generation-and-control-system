import { useCallback, useEffect, useState } from 'react';
import { getSnapshot } from './client';

// V3: все показания одной модельной минуты приходят одним ответом.
// Важное свойство: если бэкенд старый и /snapshot отсутствует, не смешиваем
// новое время с кэшированными печами — показываем явную ошибку.
export function useLive(intervalMs = 1000) {
  const [data, setData] = useState({
    battery: null, state: null, ovens: [], machines: [],
    analytics: null, feed: null, events: [],
  });
  const [error, setError] = useState(null);

  useEffect(() => {
    let alive = true;
    let timer;
    const tick = async () => {
      try {
        const snapshot = await getSnapshot();
        if (!alive) return;
        // Новый объект и новый список печей на каждом тике — гарантируем
        // свежие props для всех открытых карточек и мини-печей.
        setData({ ...snapshot, ovens: (snapshot.ovens ?? []).map((oven) => ({ ...oven })) });
        setError(null);
      } catch (e) {
        if (alive) {
          setError(new Error(
            e.message?.includes('404')
              ? 'Старый бэкенд: /api/v1/snapshot отсутствует. Пересоберите coke-app и coke-front.'
              : e.message,
          ));
        }
      } finally {
        if (alive) timer = setTimeout(tick, intervalMs);
      }
    };
    tick();
    return () => { alive = false; clearTimeout(timer); };
  }, [intervalMs]);

  // После ПУСК/ПАУЗА шапка меняется сразу; следующий единый снимок
  // в течение секунды синхронизирует остальные показания.
  const applyState = useCallback((state) => {
    setData((prev) => ({ ...prev, state }));
  }, []);

  return { ...data, error, applyState };
}
