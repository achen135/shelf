// k6 load script for the query API (M6).
//
//   k6 run -e BASE=http://localhost:8080 -e VUS=16 -e DURATION=60s scripts/k6/api.js
//   scripts/k6/sweep.sh                     # 1 → 64 VUs, one run each, tabulated
//
// Closed loop: each VU issues one request after another with no think time, so N VUs is N
// requests in flight and the run measures what the server does at that concurrency. Every
// iteration is one of the three endpoints, weighted the way a page load is — the list most,
// then a product detail, then the deals strip — with the list's filters drawn at random from
// the category's real spec schema and price range so the database sees the query mix a
// shopper would produce, not one cached plan.
import http from 'k6/http';
import { check } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const BASE = __ENV.BASE || 'http://localhost:8080';
const CATEGORY = __ENV.CATEGORY || 'keyboards';
const VUS = parseInt(__ENV.VUS || '16', 10);
const DURATION = __ENV.DURATION || '30s';

export const options = {
  scenarios: {
    mix: { executor: 'constant-vus', vus: VUS, duration: DURATION, gracefulStop: '5s' },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

// One trend per endpoint, so the summary reports each shape's latency on its own. Requests also
// carry a `name` tag per route, so k6 does not keep one time series per random URL.
const products = new Trend('products_ms', true);
const detail = new Trend('detail_ms', true);
const deals = new Trend('deals_ms', true);
const errors = new Rate('endpoint_errors');

const SORTS = ['deal', 'price', 'name'];

// Once per run: the category's spec schema (for random filters) and the product ids to detail.
export function setup() {
  const cats = http.get(`${BASE}/categories`).json();
  const cat = cats.find((c) => c.name === CATEGORY);
  if (!cat) throw new Error(`category ${CATEGORY} not served; have ${cats.map((c) => c.name)}`);
  const enums = Object.entries(cat.spec_schema)
    .filter(([, f]) => f.type === 'string' && f.values.length)
    .map(([name, f]) => ({ name, values: f.values }));
  const bools = Object.entries(cat.spec_schema)
    .filter(([, f]) => f.type === 'boolean')
    .map(([name]) => name);
  const list = http.get(`${BASE}/products?category=${CATEGORY}&limit=200&in_stock=false`).json();
  const ids = list.products.map((p) => p.id);
  if (!ids.length) throw new Error('no products to benchmark against');
  const prices = list.products.map((p) => p.offer.price_cents).filter((c) => c != null);
  return { enums, bools, ids, min: Math.min(...prices) / 100, max: Math.max(...prices) / 100 };
}

function pick(a) {
  return a[Math.floor(Math.random() * a.length)];
}

function listUrl(data) {
  const q = [`category=${CATEGORY}`, `sort=${pick(SORTS)}`, 'limit=30'];
  const r = Math.random();
  if (r < 0.6) {
    // a budget: a random window inside the observed price range
    const lo = data.min + Math.random() * (data.max - data.min) * 0.6;
    const hi = lo + (data.max - lo) * (0.3 + Math.random() * 0.7);
    q.push(`min_price=${lo.toFixed(0)}`, `max_price=${hi.toFixed(0)}`);
  }
  if (Math.random() < 0.5 && data.enums.length) {
    const f = pick(data.enums);
    q.push(`${f.name}=${encodeURIComponent(pick(f.values))}`);
  }
  if (Math.random() < 0.25 && data.bools.length) {
    q.push(`${pick(data.bools)}=true`);
  }
  return `${BASE}/products?${q.join('&')}`;
}

export default function (data) {
  const r = Math.random();
  let res;
  let trend;
  let name;
  if (r < 0.5) {
    name = 'products';
    trend = products;
    res = http.get(listUrl(data), { tags: { name: 'GET /products', endpoint: name } });
  } else if (r < 0.85) {
    name = 'detail';
    trend = detail;
    res = http.get(`${BASE}/products/${pick(data.ids)}?days=365`, {
      tags: { name: 'GET /products/{id}', endpoint: name },
    });
  } else {
    name = 'deals';
    trend = deals;
    res = http.get(`${BASE}/deals?category=${CATEGORY}`, { tags: { name: 'GET /deals', endpoint: name } });
  }
  trend.add(res.timings.duration);
  const ok = check(res, {
    'status 200': (x) => x.status === 200,
    'is json': (x) => (x.headers['Content-Type'] || '').startsWith('application/json'),
  });
  errors.add(!ok, { endpoint: name });
}
