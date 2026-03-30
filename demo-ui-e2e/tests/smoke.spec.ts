import { test, expect } from '@playwright/test';

test('loads without JS page errors and renders examples', async ({ page }) => {
  const pageErrors: Array<string> = [];
  page.on('pageerror', (err) => pageErrors.push(String(err)));

  await page.goto('/');

  await expect(page.locator('#matches .match').first()).toBeVisible();
  expect(pageErrors).toEqual([]);
});

