import http from 'k6/http';
import exec from 'k6/execution';
import { BASE_URL, SAME_SESSION_ID, RECORD_METRICS, standardOptions } from '../config.js';
import {
  authHeaders, Classification, loadBenchmarkUsers, recordResult,
  seatForOrdinal, traceId, transactionCreateDuration, transactionCreateSuccess,
  transactionDuration, transactionFailed, transactionLockDuration,
  transactionLockSuccess, transactionPaymentDuration, transactionPaymentSuccess,
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

  const lockStartedAt = Date.now();
  const lock = http.post(`${BASE_URL}/api/seat/lock`, JSON.stringify({
    scheduleId: SAME_SESSION_ID,
    seats: [seat],
  }), requestOptions);
  if (RECORD_METRICS) transactionLockDuration.add(Date.now() - lockStartedAt);
  const lockResult = recordResult(lock, 'transaction-lock');
  if (lockResult.category !== Classification.SUCCESS) return failTransaction(lockResult.category);
  if (RECORD_METRICS) transactionLockSuccess.add(1);

  const lockToken = lockResult.payload.data.lockToken;
  const createStartedAt = Date.now();
  const create = http.post(`${BASE_URL}/api/order/create`, JSON.stringify({
    scheduleId: SAME_SESSION_ID,
    lockToken,
    seats: [seat],
    seatCount: 1,
    seatsInfo: `${seat.row},${seat.col}`,
  }), requestOptions);
  if (RECORD_METRICS) transactionCreateDuration.add(Date.now() - createStartedAt);
  const createResult = recordResult(create, 'transaction-create');
  if (createResult.category !== Classification.SUCCESS) return failTransaction(createResult.category);
  if (RECORD_METRICS) transactionCreateSuccess.add(1);

  const orderNo = createResult.payload.data.orderNo;
  const paymentStartedAt = Date.now();
  const payment = http.post(
    `${BASE_URL}/api/payment/pay?orderNo=${encodeURIComponent(orderNo)}`,
    null,
    requestOptions,
  );
  if (RECORD_METRICS) transactionPaymentDuration.add(Date.now() - paymentStartedAt);
  const paymentResult = recordResult(payment, 'transaction-payment');
  if (paymentResult.category !== Classification.SUCCESS) return failTransaction(paymentResult.category);
  if (RECORD_METRICS) transactionPaymentSuccess.add(1);

  if (RECORD_METRICS) {
    transactionDuration.add(Date.now() - transactionStartedAt);
    transactionSuccess.add(1);
  }
}

function failTransaction(category) {
  if (RECORD_METRICS) transactionFailed.add(1);
  throw new Error(`transaction failed: ${category}`);
}
