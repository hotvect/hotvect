import { test, expect } from '@playwright/test';

async function getBackgroundColor(locator: import('@playwright/test').Locator) {
  return await locator.evaluate((el) => getComputedStyle(el).backgroundColor);
}

type EditableScalar = string | number | boolean;

function pickEditableTopLevelField(obj: Record<string, unknown>): { key: string; value: EditableScalar } {
  const entries = Object.entries(obj);
  for (const [key, value] of entries) {
    if (!key) continue;
    if (typeof value === 'string' && value.length > 0 && value.length <= 120) return { key, value };
  }
  for (const [key, value] of entries) {
    if (!key) continue;
    if (typeof value === 'number' && Number.isFinite(value)) return { key, value };
    if (typeof value === 'boolean') return { key, value };
  }
  throw new Error('No editable top-level scalar field found in example JSON');
}

test.describe('demo UI interactions', () => {
  test('dark dev panes + light cards', async ({ page }) => {
    await page.goto('/');

    await expect(page.locator('#sidebar')).toBeVisible();
    await expect(page.locator('#io')).toBeVisible();
    await expect(page.locator('#grid')).toBeVisible();

    await expect(await getBackgroundColor(page.locator('#sidebar'))).toBe('rgb(11, 16, 32)');
    await expect(await getBackgroundColor(page.locator('#io'))).toBe('rgb(11, 16, 32)');

    const matches = page.locator('#matches .match');
    await expect(matches.first()).toBeVisible();
    const count = await matches.count();
    await matches.nth(count > 1 ? 1 : 0).click();

    await expect(page.locator('#status')).toHaveText('OK');

    const firstCard = page.locator('#shownGrid .card, #grid .card').first();
    await expect(firstCard).toBeVisible();
    await expect(await getBackgroundColor(firstCard)).toBe('rgb(255, 255, 255)');
  });

  test('edit a JSON path and reset', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('#matches .match').first()).toBeVisible();

    const matches = page.locator('#matches .match');
    const first = matches.first();
    const exampleIndexAttr = await first.getAttribute('data-example-index');
    expect(exampleIndexAttr).toBeTruthy();
    const exampleIndex = Number(exampleIndexAttr);
    await first.click();
    await expect(page.locator('#status')).toHaveText('OK');

    const example = await (await page.request.get(`/api/examples/${exampleIndex}`)).json();
    const { key, value } = pickEditableTopLevelField(example.json);
    const path = `.${key}`;
    const newValue: EditableScalar =
      typeof value === 'string' ? `__pw_changed__${key}` : typeof value === 'number' ? value + 1 : !value;

    await page.evaluate((k) => {
      const el = document.getElementById('pathQuery');
      if (!(el instanceof HTMLInputElement)) throw new Error('missing #pathQuery');
      el.value = k;
      el.dispatchEvent(new Event('input', { bubbles: true }));
    }, key);
    await page.locator(`#pathMatches .pathMatch[data-path="${path}"]`).click();

    await page.locator('#overrideText').fill(JSON.stringify(newValue));
    await expect(page.locator('#status')).toHaveText('Not run');
    await page.locator('#run').click();
    await expect(page.locator('#status')).toHaveText('OK');
    await expect(page.locator('#inputJson')).toContainText(String(newValue));

    await page.locator('#clearOverride').click();
    await expect(page.locator('#inputJson')).toContainText(String(value));
    await expect(page.locator('#inputJson')).not.toContainText(String(newValue));
    await expect(page.locator('#status')).toHaveText('Not run');
  });

  test('selecting "." keeps editing disabled and persists across examples', async ({ page }) => {
    await page.goto('/');
    const matches = page.locator('#matches .match');
    await expect(matches.first()).toBeVisible();

    await matches.first().click();
    await expect(page.locator('#pathMatches .pathMatch').first()).toBeVisible();

    await page.locator('#pathMatches .pathMatch[data-path="."]').click();
    await expect(page.locator('#overrideText')).toBeDisabled();
    await expect(page.locator('#pathHint')).toContainText('Selected . (root)');

    const count = await matches.count();
    if (count > 1) {
      await matches.nth(1).click();
      await expect(page.locator('#overrideText')).toBeDisabled();
    }
  });

  test('typing in the path filter does not clear the selected path', async ({ page }) => {
    await page.goto('/');
    const matches = page.locator('#matches .match');
    await expect(matches.first()).toBeVisible();
    const first = matches.first();
    const exampleIndexAttr = await first.getAttribute('data-example-index');
    expect(exampleIndexAttr).toBeTruthy();
    const exampleIndex = Number(exampleIndexAttr);
    await first.click();
    await expect(page.locator('#status')).toHaveText('OK');

    const example = await (await page.request.get(`/api/examples/${exampleIndex}`)).json();
    const { key } = pickEditableTopLevelField(example.json);
    const path = `.${key}`;

    await page.evaluate((k) => {
      const el = document.getElementById('pathQuery');
      if (!(el instanceof HTMLInputElement)) throw new Error('missing #pathQuery');
      el.value = k;
      el.dispatchEvent(new Event('input', { bubbles: true }));
    }, key);
    await page.locator(`#pathMatches .pathMatch[data-path="${path}"]`).click();
    await expect(page.locator('#pathHint')).toContainText(`Selected ${path}`);

    await page.locator('#overrideText').fill('"draft-value"');
    await expect(page.locator('#status')).toHaveText('Not run');

    await page.evaluate(() => {
      const el = document.getElementById('pathQuery');
      if (!(el instanceof HTMLInputElement)) throw new Error('missing #pathQuery');
      el.value = 'relevance';
      el.dispatchEvent(new Event('input', { bubbles: true }));
    });

    await expect(page.locator('#pathHint')).toContainText(`Selected ${path}`);
    await expect(page.locator('#overrideText')).toHaveValue('"draft-value"');
    await expect(page.locator('#pathHint')).not.toContainText('Matches:');
  });

  test('invalid JSON value shows an error', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('#matches .match').first()).toBeVisible();

    const first = page.locator('#matches .match').first();
    const exampleIndexAttr = await first.getAttribute('data-example-index');
    expect(exampleIndexAttr).toBeTruthy();
    const exampleIndex = Number(exampleIndexAttr);
    await first.click();
    await expect(page.locator('#status')).toHaveText('OK');

    const example = await (await page.request.get(`/api/examples/${exampleIndex}`)).json();
    const { key } = pickEditableTopLevelField(example.json);
    const path = `.${key}`;

    await page.evaluate((k) => {
      const el = document.getElementById('pathQuery');
      if (!(el instanceof HTMLInputElement)) throw new Error('missing #pathQuery');
      el.value = k;
      el.dispatchEvent(new Event('input', { bubbles: true }));
    }, key);
    await page.locator(`#pathMatches .pathMatch[data-path="${path}"]`).click();

    await page.locator('#overrideText').fill('"unterminated');
    await page.locator('#run').click();

    await expect(page.locator('#pathHint')).toContainText('Value is not valid JSON');
    await expect(page.locator('#status')).toHaveText('Error');
  });
});
