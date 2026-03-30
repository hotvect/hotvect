import { test, expect } from '@playwright/test';

test.describe('demo UI API', () => {
  test('lists examples', async ({ request }) => {
    const resp = await request.get('/api/examples?limit=500');
    expect(resp.ok()).toBeTruthy();
    const body = await resp.json();
    expect(typeof body.count).toBe('number');
    expect(Array.isArray(body.examples)).toBeTruthy();
    expect(body.count).toBeGreaterThan(0);
  });

  test('returns an example by index', async ({ request }) => {
    const list = await (await request.get('/api/examples?limit=1')).json();
    const exampleIndex = list.examples[0].example_index;

    const resp = await request.get(`/api/examples/${exampleIndex}`);
    expect(resp.ok()).toBeTruthy();
    const body = await resp.json();
    expect(body.example_index).toBe(exampleIndex);
    expect(body.json).toBeTruthy();
    expect(typeof body.json).toBe('object');
  });

  test('returns metadata', async ({ request }) => {
    const resp = await request.get('/api/metadata');
    expect(resp.ok()).toBeTruthy();
    const body = await resp.json();
    expect(typeof body.examples_count).toBe('number');
  });

  test('rejects missing example_index', async ({ request }) => {
    const resp = await request.post('/api/run', { data: {} });
    expect(resp.status()).toBe(400);
  });

  test('rejects unknown example_index', async ({ request }) => {
    const resp = await request.post('/api/run', { data: { example_index: 999999 } });
    expect(resp.status()).toBe(404);
  });

  test('rejects override_json that is not an object', async ({ request }) => {
    const list = await (await request.get('/api/examples?limit=1')).json();
    const exampleIndex = list.examples[0].example_index;

    const resp = await request.post('/api/run', {
      data: { example_index: exampleIndex, override_json: '"not-an-object"' },
    });
    expect(resp.status()).toBe(400);
    const body = await resp.json();
    expect(JSON.stringify(body)).toContain('override_json must be a JSON object');
  });

  test('rejects example_json that is not an object', async ({ request }) => {
    const list = await (await request.get('/api/examples?limit=1')).json();
    const exampleIndex = list.examples[0].example_index;

    const resp = await request.post('/api/run', {
      data: { example_index: exampleIndex, example_json: 'oops' },
    });
    expect(resp.status()).toBe(400);
    const body = await resp.json();
    expect(JSON.stringify(body)).toContain('example_json must be a JSON object');
  });

  test('rejects example_json together with override_json', async ({ request }) => {
    const list = await (await request.get('/api/examples?limit=1')).json();
    const exampleIndex = list.examples[0].example_index;
    const example = await (await request.get(`/api/examples/${exampleIndex}`)).json();

    const resp = await request.post('/api/run', {
      data: { example_index: exampleIndex, example_json: example.json, override_json: '{}' },
    });
    expect(resp.status()).toBe(400);
    const body = await resp.json();
    expect(JSON.stringify(body)).toContain('Cannot set both example_json and override_json');
  });
});
