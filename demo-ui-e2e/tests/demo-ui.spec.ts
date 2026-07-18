import { test, expect } from '@playwright/test';

test('loads, selects an example, and renders cards', async ({ page }) => {
  await page.goto('/');

  await expect(page).toHaveTitle(/🌶️ Algorithm Demo UI ·/);
  await expect(page.locator('#appTitle .brandPepper')).toHaveText('🌶️');

  const matches = page.locator('#matches .match');
  await expect(matches.first()).toBeVisible();

  const count = await matches.count();
  const example = matches.nth(count > 1 ? 1 : 0);
  await example.click();

  await expect(page.locator('#status')).toHaveText('OK', { timeout: 15000 });
  await expect(page.locator('#comparisonHeader')).toHaveCount(0);
  await expect(page.getByRole('combobox', { name: 'Baseline algorithm version' })).toBeVisible();
  await expect(page.locator('#grids .columnRole')).toHaveText('Baseline');
  await expect(page.locator('#grids .badge').first()).toHaveCSS('color', 'rgb(255, 255, 255)');
  await expect(page.locator('#grids .grid').first()).toBeVisible();
  await expect(page.locator('#grids .gridPanel')).toHaveCount(1);
});
