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
  test('runs the conventional shared query input without JSON path discovery', async ({ page }) => {
    await page.goto('/');

    const primaryInput = page.getByRole('textbox', { name: 'Query', exact: true });
    await expect(primaryInput).toBeVisible();
    await expect(primaryInput).toHaveValue('alpha');
    await expect(page.locator('#primaryInputHint')).toContainText('.shared.query');

    await primaryInput.fill('new input value');
    await expect(page.locator('#status')).toHaveText('Not run');
    await expect(page.locator('#inputJson')).toContainText('new input value');

    await primaryInput.press('Enter');
    await expect(page.locator('#status')).toHaveText('OK');
    await expect(page.locator('#primaryInputRun')).toBeDisabled();
    await expect(primaryInput).toHaveValue('new input value');
  });

  test('hides the query input when an example has no shared query', async ({ page }) => {
    await page.goto('/');

    await expect(page.locator('#primaryInputWrap')).toBeVisible();
    const matches = page.locator('#matches .match');
    await expect(matches.nth(1)).toBeVisible();
    await matches.nth(1).click();
    await expect(page.locator('#primaryInputWrap')).toBeHidden();
  });

  test('uses the Hotvect docsite light palette', async ({ page }) => {
    await page.goto('/');

    await expect(page.locator('#sidebar')).toBeVisible();
    await expect(page.locator('#io')).toBeVisible();
    await expect(page.locator('#grids .grid').first()).toBeVisible();

    await expect(await getBackgroundColor(page.locator('body'))).toBe('rgb(251, 250, 247)');
    await expect(await getBackgroundColor(page.locator('#sidebar'))).toBe('rgb(255, 255, 255)');
    await expect(await getBackgroundColor(page.locator('#io'))).toBe('rgb(240, 237, 231)');
    await expect(page.locator('#appTitle')).toHaveCSS('color', 'rgb(185, 61, 30)');
    const metadataLabel = page.locator('#algoMeta .metaLabel').first();
    const metadataValue = page.locator('#algoMeta .metaValue').first();
    await expect(metadataLabel).toHaveText('Algo:');
    await expect(metadataValue).toContainText('@');
    const labelColor = await metadataLabel.evaluate((element) => getComputedStyle(element).color);
    const valueColor = await metadataValue.evaluate((element) => getComputedStyle(element).color);
    expect(valueColor).not.toBe(labelColor);
    await expect(metadataValue).toHaveCSS('font-weight', '600');

    const matches = page.locator('#matches .match');
    await expect(matches.first()).toBeVisible();
    const count = await matches.count();
    await matches.nth(count > 1 ? 1 : 0).click();

    await expect(page.locator('#status')).toHaveText('OK');

    const firstCard = page.locator('#grids .card').first();
    await expect(firstCard).toBeVisible();
    await expect(await getBackgroundColor(firstCard)).toBe('rgb(255, 255, 255)');
  });

  test('opens effective algorithm definition and parameter metadata from the header', async ({ page }) => {
    await page.goto('/');

    const algoTrigger = page.locator('#algoMeta');
    const parameterTrigger = page.locator('#paramMeta');
    await expect(algoTrigger).toBeEnabled();
    await expect(parameterTrigger).toBeEnabled();

    await algoTrigger.click();
    await expect(page.locator('#runtimeMetadataModal')).toHaveAttribute('aria-hidden', 'false');
    await expect(page.locator('#runtimeMetadataModalTitle')).toHaveText('Effective algorithm definition');
    await expect(page.locator('#runtimeMetadataModalJson')).toContainText('algorithm_name');
    await expect(page.locator('#runtimeMetadataModalJson')).toContainText('demo-ranker');

    await page.locator('#runtimeMetadataModalClose').click();
    await expect(page.locator('#runtimeMetadataModal')).toHaveAttribute('aria-hidden', 'true');
    await expect(algoTrigger).toBeFocused();

    await parameterTrigger.click();
    await expect(page.locator('#runtimeMetadataModalTitle')).toHaveText('Parameter metadata');
    await expect(page.locator('#runtimeMetadataModalJson')).toContainText('parameter_id');
    await expect(page.locator('#runtimeMetadataModalJson')).toContainText('e2e-local');

    await page.keyboard.press('Escape');
    await expect(page.locator('#runtimeMetadataModal')).toHaveAttribute('aria-hidden', 'true');
    await expect(parameterTrigger).toBeFocused();
  });

  test('renders embedded action images without expanding their payload in metadata', async ({ page }) => {
    await page.goto('/');

    const firstExample = page.locator('#matches .match').first();
    await expect(firstExample).toBeVisible();
    await firstExample.click();
    await expect(page.locator('#status')).toHaveText('OK');

    const firstCard = page.locator('#grids .card').first();
    await expect(firstCard).toBeVisible();
    await expect(firstCard.locator('.thumb')).toHaveCSS('background-image', /data:image\/png;base64/);
    await firstCard.click();

    await expect(page.locator('#actionModal')).toHaveAttribute('aria-hidden', 'false');
    const image = page.locator('#actionModalImage');
    await expect(image).toBeVisible();
    await expect.poll(() => image.evaluate((element) => (element as HTMLImageElement).naturalWidth)).toBe(1);
    await expect(page.locator('#actionModalJson')).toContainText('data:image/png;base64,…');
    await expect(page.locator('#actionModalJson')).toContainText('CC0-1.0');
    await expect(page.locator('#actionModalJson')).not.toContainText('iVBORw0KGgo');
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

  test('edits a field inside the candidate array', async ({ page }) => {
    await page.goto('/');
    const firstExample = page.locator('#matches .match').first();
    await expect(firstExample).toBeVisible();
    await firstExample.click();
    await expect(page.locator('#status')).toHaveText('OK');

    await page.locator('#pathQuery').fill('.actions[0].label');
    const candidateLabelPath = page.locator('#pathMatches .pathMatch[data-path=".actions[0].label"]');
    await expect(candidateLabelPath).toBeVisible();
    await candidateLabelPath.click();
    await page.locator('#overrideText').fill('"Edited candidate label"');
    await page.locator('#run').click();

    await expect(page.locator('#status')).toHaveText('OK');
    await expect(page.locator('#inputJson')).toContainText('Edited candidate label');
  });

  test('uses native buttons for examples, paths, and result cards', async ({ page }) => {
    await page.goto('/');
    const firstExample = page.locator('#matches .match').first();
    await expect(firstExample).toBeVisible();
    await expect(firstExample).toHaveJSProperty('tagName', 'BUTTON');
    await firstExample.click();
    await expect(page.locator('#status')).toHaveText('OK');

    await expect(page.locator('#pathMatches .pathMatch').first()).toHaveJSProperty('tagName', 'BUTTON');
    await expect(page.locator('#grids .card').first()).toHaveJSProperty('tagName', 'BUTTON');
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
