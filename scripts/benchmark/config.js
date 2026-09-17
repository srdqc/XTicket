export const BASE_URL = __ENV.BASE_URL || 'http://localhost';
export const PHASE = __ENV.PHASE || 'measurement';
export const VUS = Number.parseInt(__ENV.VUS || '1', 10);
export const WARMUP_DURATION = __ENV.WARMUP_DURATION || '30s';
export const MEASUREMENT_DURATION = __ENV.DURATION || '60s';
export const DURATION = PHASE === 'warmup' ? WARMUP_DURATION : MEASUREMENT_DURATION;
export const RECORD_METRICS = PHASE !== 'warmup';

export const BENCHMARK_SESSIONS = [
  910001, 910002, 910003, 910004,
  910005, 910006, 910007, 910008,
];

export const SAME_SESSION_ID = Number.parseInt(
  __ENV.SESSION_ID || String(BENCHMARK_SESSIONS[0]),
  10,
);
export const SEAT_ROWS = 100;
export const SEAT_COLS = 200;

export function standardOptions() {
  return {
    vus: VUS,
    duration: DURATION,
    gracefulStop: '5s',
    thresholds: RECORD_METRICS ? {
      system_error: ['count==0'],
      network_error: ['count==0'],
      timeout_error: ['count==0'],
      parse_error: ['count==0'],
    } : {},
  };
}
