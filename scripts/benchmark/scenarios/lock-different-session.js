import http from 'k6/http';
import exec from 'k6/execution';
import { BASE_URL, BENCHMARK_SESSIONS, standardOptions } from '../config.js';
import {
  authHeaders, Classification, loadBenchmarkUsers, recordResult,
  seatForOrdinal, traceId, userForIteration,
} from '../helpers.js';

export const options = standardOptions();
const users = loadBenchmarkUsers();

export default function () {
  const iteration = exec.scenario.iterationInTest;
  const sessionIndex = iteration % BENCHMARK_SESSIONS.length;
  const sessionId = BENCHMARK_SESSIONS[sessionIndex];
  const seat = seatForOrdinal(Math.floor(iteration / BENCHMARK_SESSIONS.length));
  const user = userForIteration(users, iteration);
  const response = http.post(`${BASE_URL}/api/seat/lock`, JSON.stringify({
    scheduleId: sessionId,
    seats: [seat],
  }), authHeaders(user, traceId('lock-different', iteration)));
  const result = recordResult(response, 'lock-different-session');
  if (result.category !== Classification.SUCCESS) {
    throw new Error(`lock failed: ${result.category}`);
  }
}
