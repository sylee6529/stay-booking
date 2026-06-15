import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  vus: Number(__ENV.VUS || 100),
  iterations: Number(__ENV.ITERATIONS || 1000),
  thresholds: {
    http_req_failed: ['rate<0.20'],
    http_req_duration: ['p(95)<2000'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const PRODUCT_ID = Number(__ENV.PRODUCT_ID || 1);

export default function () {
  const userId = 1000000 + __VU * 100000 + __ITER;
  const key = `k6-${userId}`;
  const payload = JSON.stringify({
    productId: PRODUCT_ID,
    userId,
    paymentMethods: ['CREDIT_CARD'],
    pointAmount: 0,
    cardNumber: '4111-1111-1111-1234',
  });

  const res = http.post(`${BASE_URL}/api/bookings`, payload, {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': key,
    },
  });

  check(res, {
    'expected status': (r) => [200, 409, 422, 503].includes(r.status),
  });

  sleep(0.01);
}
