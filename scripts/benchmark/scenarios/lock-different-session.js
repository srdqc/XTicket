import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { BASE_URL, BENCHMARK_SESSIONS, VUS, standardOptions } from '../config.js';
import {
  authHeaders, Classification, loadBenchmarkUsers, recordResult,
  seatForOrdinal, traceId, userForVuIteration,
} from '../helpers.js';

export const options = standardOptions();
const users = loadBenchmarkUsers();

export default function () {
  const iteration = exec.scenario.iterationInTest;
  const sessionIndex = iteration % BENCHMARK_SESSIONS.length;
  const sessionId = BENCHMARK_SESSIONS[sessionIndex];
  const seat = seatForOrdinal(Math.floor(iteration / BENCHMARK_SESSIONS.length));
  const user = userForVuIteration(users, exec.vu.idInTest, exec.vu.iterationInScenario, VUS);
  const response = http.post(`${BASE_URL}/api/seat/lock`, JSON.stringify({
    scheduleId: sessionId,
    seats: [seat],
  }), authHeaders(user, traceId('lock-different', iteration)));
  const result = recordResult(response, 'lock-different-session');
  sleep(Number.parseFloat(__ENV.ITERATION_PACING_SECONDS || '0.5'));
  if (result.category !== Classification.SUCCESS) {
    throw new Error(`lock failed: ${result.category}`);
  }
}
