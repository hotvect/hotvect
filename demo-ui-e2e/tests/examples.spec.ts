import { test, expect } from '@playwright/test';

test.describe('example browsing', () => {
  test('shows example count and renders the returned list', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('#matches .match').first()).toBeVisible();

    const api = await (await page.request.get('/api/examples?limit=500')).json();
    const shown = (api.examples as Array<unknown>).length;

    await expect(page.locator('#matchesTitle')).toContainText('Examples (');
    await expect(page.locator('#matches .match')).toHaveCount(shown);
    await expect(page.getByText('Browse examples', { exact: true })).toBeVisible();
    await expect(page.locator('#matches .match .secondary').first()).not.toBeEmpty();
  });

  test('filters examples by id or request preview', async ({ page }) => {
    await page.goto('/');
    const api = await (await page.request.get('/api/examples?limit=500')).json();
    const examples = api.examples as Array<{ example_id: string }>;
    expect(examples.length).toBeGreaterThan(0);

    await page.getByRole('searchbox', { name: 'Search examples' }).fill(examples[0].example_id);
    await expect(page.locator('#matches .match')).toHaveCount(1);
    await expect(page.locator('#matches .match').first()).toContainText(examples[0].example_id);
    await expect(page.locator('#matchesTitle')).toContainText(`1/${examples.length}`);

    await page.getByRole('searchbox', { name: 'Search examples' }).fill('');
    await expect(page.locator('#matches .match')).toHaveCount(examples.length);
  });

  test('selects a match and auto-runs', async ({ page }) => {
    await page.goto('/');
    const matches = page.locator('#matches .match');
    await expect(matches.first()).toBeVisible();
    await matches.first().click();
    await expect(page.locator('#status')).toHaveText('OK');
    await expect(page.locator('#grids .grid').first()).toBeVisible();
  });
});
