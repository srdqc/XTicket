import http from 'k6/http';
import { BASE_URL, SAME_SESSION_ID, standardOptions } from '../config.js';
import { recordResult, Classification } from '../helpers.js';

export const options = standardOptions();

export default function () {
  const response = http.get(`${BASE_URL}/api/seat/layout?scheduleId=${SAME_SESSION_ID}`, {
    timeout: __ENV.HTTP_TIMEOUT || '10s',
  });
  const result = recordResult(response, 'seat-layout');
  if (result.category !== Classification.SUCCESS) {
    throw new Error(`seat layout failed: ${result.category}`);
  }
}
