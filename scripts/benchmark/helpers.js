import { Counter, Trend } from 'k6/metrics';
import { check } from 'k6';
import { RECORD_METRICS, SEAT_COLS, SEAT_ROWS } from './config.js';

export const appSuccess = new Counter('app_success');
export const businessConflict = new Counter('business_conflict');
export const rateLimited = new Counter('rate_limited');
export const authFailure = new Counter('auth_failure');
export const systemError = new Counter('system_error');
export const networkError = new Counter('network_error');
export const timeoutError = new Counter('timeout_error');
export const parseError = new Counter('parse_error');
export const transactionSuccess = new Counter('transaction_success');
export const transactionFailed = new Counter('transaction_failed');
export const transactionDuration = new Trend('transaction_duration', true);
export const transactionLockDuration = new Trend('transaction_lock_duration', true);
export const transactionCreateDuration = new Trend('transaction_create_duration', true);
export const transactionPaymentDuration = new Trend('transaction_payment_duration', true);
export const transactionLockSuccess = new Counter('transaction_lock_success');
export const transactionCreateSuccess = new Counter('transaction_create_success');
export const transactionPaymentSuccess = new Counter('transaction_payment_success');

export const Classification = Object.freeze({
  SUCCESS: 'SUCCESS',
  BUSINESS_CONFLICT: 'BUSINESS_CONFLICT',
  RATE_LIMITED: 'RATE_LIMITED',
  AUTH_FAILURE: 'AUTH_FAILURE',
  SYSTEM_ERROR: 'SYSTEM_ERROR',
  NETWORK_ERROR: 'NETWORK_ERROR',
  TIMEOUT: 'TIMEOUT',
  PARSE_ERROR: 'PARSE_ERROR',
});

export function classifyResult(response) {
  if (!response || response.status === 0 || response.error) {
    const message = response && response.error ? response.error.toLowerCase() : '';
    return { category: message.includes('timeout') ? Classification.TIMEOUT : Classification.NETWORK_ERROR };
  }

  let payload;
  try {
    payload = response.json();
  } catch (_) {
    return { category: Classification.PARSE_ERROR };
  }

  const code = Number(payload.code);
  if (code === 200) return { category: Classification.SUCCESS, payload };
  if (code === 429) return { category: Classification.RATE_LIMITED, payload };
  if (code === 401 || code === 403) return { category: Classification.AUTH_FAILURE, payload };
  if (code === 409 || (code >= 460 && code <= 463) || (code >= 400 && code < 500)) {
    return { category: Classification.BUSINESS_CONFLICT, payload };
  }
  if (code >= 500 || response.status >= 500) return { category: Classification.SYSTEM_ERROR, payload };
  return { category: Classification.SYSTEM_ERROR, payload };
}

export function recordResult(response, label) {
  const result = classifyResult(response);
  check(result, { [`${label}: Result.code classified`]: (value) => Boolean(value.category) });
  if (!RECORD_METRICS) return result;

  switch (result.category) {
    case Classification.SUCCESS: appSuccess.add(1); break;
    case Classification.BUSINESS_CONFLICT: businessConflict.add(1); break;
    case Classification.RATE_LIMITED: rateLimited.add(1); break;
    case Classification.AUTH_FAILURE: authFailure.add(1); break;
    case Classification.NETWORK_ERROR: networkError.add(1); break;
    case Classification.TIMEOUT: timeoutError.add(1); break;
    case Classification.PARSE_ERROR: parseError.add(1); break;
    default: systemError.add(1);
  }
  return result;
}

export function loadBenchmarkUsers() {
  const path = __ENV.TOKEN_FILE || import.meta.resolve('./results/raw/tokens.json');
  const users = JSON.parse(open(path));
  if (!Array.isArray(users) || users.length === 0) {
    throw new Error(`No benchmark users in ${path}; run prepare.ps1 first`);
  }
  return users;
}

export function userForIteration(users, iteration) {
  return users[iteration % users.length];
}

export function userForVuIteration(users, vuId, iteration, totalVUs) {
  if (!Number.isInteger(vuId) || vuId < 1 || !Number.isInteger(totalVUs) || totalVUs < 1) {
    throw new Error(`Invalid VU partition: vuId=${vuId}, totalVUs=${totalVUs}`);
  }
  const usersPerVu = Math.floor(users.length / totalVUs);
  if (usersPerVu < 1 || vuId > totalVUs) {
    throw new Error(`User pool cannot partition ${users.length} users across ${totalVUs} VUs`);
  }
  const partitionStart = (vuId - 1) * usersPerVu;
  return users[partitionStart + (iteration % usersPerVu)];
}

export function authHeaders(user, traceId) {
  return {
    headers: {
      Authorization: `Bearer ${user.token}`,
      'Content-Type': 'application/json',
      'X-Trace-Id': traceId,
    },
    timeout: __ENV.HTTP_TIMEOUT || '10s',
  };
}

export function seatForOrdinal(ordinal) {
  if (ordinal < 0 || ordinal >= SEAT_ROWS * SEAT_COLS) {
    throw new Error(`Fixture seat capacity exhausted at ordinal ${ordinal}`);
  }
  return {
    row: Math.floor(ordinal / SEAT_COLS) + 1,
    col: (ordinal % SEAT_COLS) + 1,
  };
}

export function traceId(scenario, iteration) {
  return `bench-${scenario}-${iteration}`;
}
