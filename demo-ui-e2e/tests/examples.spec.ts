import { test, expect } from '@playwright/test';

test.describe('example browsing', () => {
  test('shows example count and renders the returned list', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('#matches .match').first()).toBeVisible();

    const api = await (await page.request.get('/api/examples?limit=500')).json();
    const shown = (api.examples as Array<unknown>).length;

    await expect(page.locator('#matchesTitle')).toContainText('Examples (');
    await expect(page.locator('#matches .match')).toHaveCount(shown);
  });

  test('selects a match and auto-runs', async ({ page }) => {
    await page.goto('/');
    const matches = page.locator('#matches .match');
    await expect(matches.first()).toBeVisible();
    await matches.first().click();
    await expect(page.locator('#status')).toHaveText('OK');
    await expect(page.locator('#grid')).toBeVisible();
  });
});
