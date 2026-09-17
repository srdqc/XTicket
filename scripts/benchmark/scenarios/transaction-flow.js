import http from 'k6/http';
import exec from 'k6/execution';
import { BASE_URL, SAME_SESSION_ID, RECORD_METRICS, standardOptions } from '../config.js';
import {
  authHeaders, Classification, loadBenchmarkUsers, recordResult,
  seatForOrdinal, traceId, transactionDuration, transactionFailed,
  transactionSuccess, userForIteration,
} from '../helpers.js';

export const options = standardOptions();
const users = loadBenchmarkUsers();

export default function () {
  const iteration = exec.scenario.iterationInTest;
  const user = userForIteration(users, iteration);
  const seat = seatForOrdinal(iteration);
  const trace = traceId('transaction', iteration);
  const requestOptions = authHeaders(user, trace);
  const transactionStartedAt = Date.now();

  const lock = http.post(`${BASE_URL}/api/seat/lock`, JSON.stringify({
    scheduleId: SAME_SESSION_ID,
    seats: [seat],
  }), requestOptions);
  const lockResult = recordResult(lock, 'transaction-lock');
  if (lockResult.category !== Classification.SUCCESS) return failTransaction(lockResult.category);

  const lockToken = lockResult.payload.data.lockToken;
  const create = http.post(`${BASE_URL}/api/order/create`, JSON.stringify({
    scheduleId: SAME_SESSION_ID,
    lockToken,
    seats: [seat],
    seatCount: 1,
    seatsInfo: `${seat.row},${seat.col}`,
  }), requestOptions);
  const createResult = recordResult(create, 'transaction-create');
  if (createResult.category !== Classification.SUCCESS) return failTransaction(createResult.category);

  const orderNo = createResult.payload.data.orderNo;
  const payment = http.post(
    `${BASE_URL}/api/payment/pay?orderNo=${encodeURIComponent(orderNo)}`,
    null,
    requestOptions,
  );
  const paymentResult = recordResult(payment, 'transaction-payment');
  if (paymentResult.category !== Classification.SUCCESS) return failTransaction(paymentResult.category);

  if (RECORD_METRICS) {
    transactionDuration.add(Date.now() - transactionStartedAt);
    transactionSuccess.add(1);
  }
}

function failTransaction(category) {
  if (RECORD_METRICS) transactionFailed.add(1);
  throw new Error(`transaction failed: ${category}`);
}
