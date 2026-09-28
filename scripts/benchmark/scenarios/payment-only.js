import http from 'k6/http';
import exec from 'k6/execution';
import { sleep } from 'k6';
import { BASE_URL, RECORD_METRICS, standardOptions } from '../config.js';
import {
  authHeaders, Classification, paymentOnlyDuration, paymentOnlySuccess,
  recordResult, traceId,
} from '../helpers.js';

export const options = standardOptions();
const orderFile = __ENV.PAYMENT_ORDER_FILE;
if (!orderFile) throw new Error('PAYMENT_ORDER_FILE is required');
const orders = JSON.parse(open(orderFile));
if (!Array.isArray(orders) || orders.length === 0) throw new Error('Payment order fixture is empty');

export default function () {
  const iteration = exec.scenario.iterationInTest;
  if (iteration >= orders.length) throw new Error(`Payment fixture exhausted at ${iteration}`);
  const order = orders[iteration];
  const startedAt = Date.now();
  const response = http.post(
    `${BASE_URL}/api/payment/pay?orderNo=${encodeURIComponent(order.orderNo)}`,
    null,
    authHeaders(order, traceId('payment-only', iteration)),
  );
  if (RECORD_METRICS) paymentOnlyDuration.add(Date.now() - startedAt);
  const result = recordResult(response, 'payment-only');
  if (result.category === Classification.SUCCESS) {
    if (RECORD_METRICS) paymentOnlySuccess.add(1);
  }
  sleep(0.5);
}
