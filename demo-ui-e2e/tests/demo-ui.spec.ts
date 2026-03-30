import { test, expect } from '@playwright/test';

test('loads, selects an example, and renders cards', async ({ page }) => {
  await page.goto('/');

  const matches = page.locator('#matches .match');
  await expect(matches.first()).toBeVisible();

  const count = await matches.count();
  const example = matches.nth(count > 1 ? 1 : 0);
  await example.click();

  await expect(page.locator('#status')).toHaveText('OK');
  await expect(page.locator('#grid')).toBeVisible();
});
