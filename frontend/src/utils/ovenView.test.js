// Запуск без сборщика: node --test frontend/src/utils/ovenView.test.js
import test from 'node:test';
import assert from 'node:assert/strict';
import { ovenProgress, ovenView, overholdShade, legendColor } from './ovenView.js';

const battery = { cokingMin: 830 };
const stateAt = (minute) => ({ simTime: new Date(Date.UTC(2026, 9, 5, 1, minute)).toISOString() });

test('прогресс коксования зависит от живого времени, даже если progressPct застрял', () => {
  const oven = { state: 'coking', progressPct: 0.1, readyAt: stateAt(830).simTime };
  assert.equal(ovenProgress(oven, stateAt(0), battery), 0);
  assert.ok(Math.abs(ovenProgress(oven, stateAt(100), battery) - 100 * 100 / 830) < 1e-7);
  assert.ok(ovenProgress(oven, stateAt(120), battery) > ovenProgress(oven, stateAt(100), battery));
  assert.equal(ovenProgress(oven, stateAt(830), battery), 100);
});

test('новый API: учёт продлённого коксования после отказа обогрева', () => {
  const oven = {
    state: 'coking', progressPct: 3.1,
    cokingStartedAt: stateAt(0).simTime,
    readyAt: stateAt(1000).simTime,
  };
  assert.equal(ovenProgress(oven, stateAt(250), battery), 25);
  assert.equal(ovenProgress(oven, stateAt(500), battery), 50);
});

test('пауза не изменяет процент, начало/конец обрезаются до 0..100', () => {
  const oven = { state: 'coking', progressPct: 12.2, readyAt: stateAt(830).simTime };
  assert.equal(ovenProgress(oven, stateAt(100), battery), ovenProgress(oven, stateAt(100), battery));
  assert.equal(ovenProgress(oven, stateAt(-20), battery), 0);
  assert.equal(ovenProgress(oven, stateAt(850), battery), 100);
});

test('другие состояния и резервное значение API', () => {
  assert.equal(ovenProgress({ state: 'ready', progressPct: 0 }, stateAt(0), battery), 100);
  assert.equal(ovenProgress({ state: 'overhold', progressPct: 10 }, stateAt(0), battery), 100);
  assert.equal(ovenProgress({ state: 'discharging', progressPct: 94.1 }, stateAt(0), battery), 0);
  assert.equal(ovenProgress({ state: 'fault', progressPct: 20 }, stateAt(0), battery), 0);
  assert.equal(ovenProgress({ state: 'coking', progressPct: 76.1 }, null, null), 76.1);
});

// Палитра по правке куратора (#27): жёлтый градаций — срыв по масштабу,
// красный — авария, оранжевый — вне серии. Жёлтый больше не штатное коксование.
test('жёлтый градациями отражает масштаб простоя, авария красная', () => {
  assert.equal(overholdShade(10, 5), '#FDE047');
  assert.equal(overholdShade(30, 5), '#FACC15');
  assert.equal(overholdShade(100, 5), '#EAB308');
  const view = ovenView({ state: 'overhold', deviationMin: 90 }, null, null);
  assert.equal(view.color, '#EAB308');
  assert.match(view.stat, /Срыв/);
  assert.equal(ovenView({ state: 'fault' }, null, null).color, '#EF4444');
  assert.equal(ovenView({ state: 'unavailable' }, null, null).color, '#F97316');
  assert.notEqual(ovenView({ state: 'coking' }, null, null).color, '#EAB308');
  assert.notEqual(legendColor('coking'), '#EAB308');
});

// Регрессия по реальному случаю: API прогрессирует, время в карточке тоже;
// процент не должен оставаться на 43.2% от момента открытия.
test('открытая карточка П-2 получает новый прогресс при следующем снимке', () => {
  const oven = {
    number: 2,
    state: 'coking',
    progressPct: 43.2,
    cokingStartedAt: '2026-10-04T19:01:47Z',
    readyAt: '2026-10-05T08:51:47Z',
  };
  const before = { simTime: '2026-10-05T01:00:00Z' };
  const after = { simTime: '2026-10-05T07:02:00Z' };
  const value1 = ovenView(oven, before, battery).pc;
  const value2 = ovenView(oven, after, battery).pc;
  assert.ok(Math.abs(value1 - 43.2) < 0.2);
  assert.ok(Math.abs(value2 - 86.8) < 0.2);
  assert.ok(value2 > value1);
});
