import http from 'k6/http';
import exec from 'k6/execution';
import { BASE_URL, SAME_SESSION_ID, RECORD_METRICS } from '../config.js';
import {
  authHeaders, Classification, loadBenchmarkUsers, recordResult, traceId,
} from '../helpers.js';

const contenders = Number.parseInt(__ENV.CONTENDERS || '5', 10);
export const options = {
  scenarios: {
    contention: {
      executor: 'shared-iterations',
      vus: contenders,
      iterations: contenders,
      maxDuration: '15s',
    },
  },
  thresholds: RECORD_METRICS ? {
    app_success: ['count==1'],
    business_conflict: [`count==${contenders - 1}`],
    system_error: ['count==0'],
  } : {},
};
const users = loadBenchmarkUsers();

export default function () {
  const iteration = exec.scenario.iterationInTest;
  const user = users[iteration % users.length];
  const response = http.post(`${BASE_URL}/api/seat/lock`, JSON.stringify({
    scheduleId: SAME_SESSION_ID,
    seats: [{ row: 1, col: 1 }],
  }), authHeaders(user, traceId('same-seat', iteration)));
  const result = recordResult(response, 'same-seat-contention');
  if (result.category !== Classification.SUCCESS
      && result.category !== Classification.BUSINESS_CONFLICT) {
    throw new Error(`unexpected contention result: ${result.category}`);
  }
}
