import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { BASE_URL, SAME_SESSION_ID, VUS, standardOptions } from '../config.js';
import {
  authHeaders, Classification, loadBenchmarkUsers, recordResult,
  seatForOrdinal, traceId, userForVuIteration,
} from '../helpers.js';

export const options = standardOptions();
const users = loadBenchmarkUsers();

export default function () {
  const iteration = exec.scenario.iterationInTest;
  const user = userForVuIteration(users, exec.vu.idInTest, exec.vu.iterationInScenario, VUS);
  const seat = seatForOrdinal(iteration);
  const response = http.post(`${BASE_URL}/api/seat/lock`, JSON.stringify({
    scheduleId: SAME_SESSION_ID,
    seats: [seat],
  }), authHeaders(user, traceId('lock-same', iteration)));
  const result = recordResult(response, 'lock-same-session');
  sleep(Number.parseFloat(__ENV.ITERATION_PACING_SECONDS || '0.5'));
  if (result.category !== Classification.SUCCESS) {
    throw new Error(`lock failed: ${result.category}`);
  }
}
