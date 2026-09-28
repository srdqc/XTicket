import http from 'k6/http';
import { BASE_URL, standardOptions } from '../config.js';
import { recordResult, Classification } from '../helpers.js';

export const options = standardOptions();

export default function () {
  const response = http.get(`${BASE_URL}/api/activities?page=1&pageSize=30&sort=hot`, {
    timeout: __ENV.HTTP_TIMEOUT || '10s',
  });
  const result = recordResult(response, 'activities');
  if (result.category !== Classification.SUCCESS) {
    throw new Error(`activities failed: ${result.category}`);
  }
}
