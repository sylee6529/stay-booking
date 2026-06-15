import http from 'k6/http';
import { check, sleep } from 'k6';

http.setResponseCallback(http.expectedStatuses(200, 409, 429));

export const options = {
  vus: Number(__ENV.VUS || 1),
  iterations: Number(__ENV.ITERATIONS || 20),
  thresholds: {
    http_req_duration: ['p(95)<1000'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const PRODUCT_ID = Number(__ENV.PRODUCT_ID || 1);
const USER_ID = Number(__ENV.USER_ID || 7777777);

export default function () {
  const payload = JSON.stringify({
    productId: PRODUCT_ID,
    userId: USER_ID,
    paymentMethods: ['CREDIT_CARD'],
    pointAmount: 0,
    cardNumber: '4111-1111-1111-1234',
  });

  const res = http.post(`${BASE_URL}/api/bookings`, payload, {
    headers: {
      'Content-Type': 'application/json',
      'Idempotency-Key': `rate-limit-${USER_ID}-${__ITER}`,
    },
  });

  check(res, {
    'expected status': (r) => [200, 409, 429].includes(r.status),
    'rate limited after burst': (r) => __ITER < 5 || r.status === 429 || r.status === 409,
  });

  sleep(0.01);
}
