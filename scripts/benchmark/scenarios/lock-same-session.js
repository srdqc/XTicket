import http from 'k6/http';
import exec from 'k6/execution';
import { BASE_URL, SAME_SESSION_ID, standardOptions } from '../config.js';
import {
  authHeaders, Classification, loadBenchmarkUsers, recordResult,
  seatForOrdinal, traceId, userForIteration,
} from '../helpers.js';

export const options = standardOptions();
const users = loadBenchmarkUsers();

export default function () {
  const iteration = exec.scenario.iterationInTest;
  const user = userForIteration(users, iteration);
  const seat = seatForOrdinal(iteration);
  const response = http.post(`${BASE_URL}/api/seat/lock`, JSON.stringify({
    scheduleId: SAME_SESSION_ID,
    seats: [seat],
  }), authHeaders(user, traceId('lock-same', iteration)));
  const result = recordResult(response, 'lock-same-session');
  if (result.category !== Classification.SUCCESS) {
    throw new Error(`lock failed: ${result.category}`);
  }
}
