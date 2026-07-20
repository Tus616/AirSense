import { chromium } from "playwright";

const BASE_URL = process.env.UQI_BASE_URL || "http://localhost:5173";
const screenshots = [
  "final-authenticated-overview.png",
  "final-overview-desktop.png",
  "final-overview-mobile.png",
  "final-historical-replay-gomti.png",
  "final-historical-replay-ito.png",
  "final-historical-replay-bkc.png",
  "final-historical-replay-missing-actual.png",
  "final-map-leaflet.png",
  "final-map-degraded-layer.png",
  "final-enforcement.png",
  "final-health-advisory.png",
  "final-copilot-open.png",
  "final-copilot-closed.png",
  "final-api-error-state.png",
];

const chromePath = "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe";
const consoleMessages = [];
const pageErrors = [];

function wireDiagnostics(page) {
  page.on("console", (message) => {
    consoleMessages.push({
      type: message.type(),
      text: message.text(),
      url: message.location()?.url,
    });
  });
  page.on("pageerror", (error) => {
    pageErrors.push(error.message);
  });
}

async function settle(page) {
  await page.waitForLoadState("domcontentloaded");
  await page.waitForTimeout(1200);
}

async function login(page) {
  try {
    await page.goto(`${BASE_URL}/login`, { waitUntil: "domcontentloaded", timeout: 60000 });
  } catch (error) {
    console.warn(`login navigation retry: ${error.message}`);
    await page.goto(`${BASE_URL}/login`, { waitUntil: "commit", timeout: 60000 });
  }
  await page.getByRole("button", { name: /Login as Municipal Official/i }).click();
  await page.waitForURL(/\/gov/, { timeout: 20000 });
  await page.waitForSelector("text=Urban Air Quality Intelligence", { timeout: 20000 });
  await settle(page);
}

async function selectLucknow(page) {
  const button = page.getByRole("button", { name: /^Lucknow$/i });
  if (await button.count()) {
    await button.first().click();
    await page.waitForSelector("text=Lucknow, Uttar Pradesh, India", { timeout: 20000 }).catch(() => {});
  }
  await page.waitForTimeout(5000);
}

async function selectCity(page, name, expectedText) {
  const presets = {
    Lucknow: {
      cityId: "LUCKNOW",
      cityName: "Lucknow",
      displayName: "Lucknow, Uttar Pradesh, India",
      latitude: 26.8467,
      longitude: 80.9462,
      state: "Uttar Pradesh",
      country: "India",
    },
    Delhi: {
      cityId: "DELHI",
      cityName: "Delhi",
      displayName: "Delhi, India",
      latitude: 28.6139,
      longitude: 77.209,
      state: "Delhi",
      country: "India",
    },
    Mumbai: {
      cityId: "MUMBAI",
      cityName: "Mumbai",
      displayName: "Mumbai, Maharashtra, India",
      latitude: 19.076,
      longitude: 72.8777,
      state: "Maharashtra",
      country: "India",
    },
  };
  await page.evaluate(async (city) => {
    const storeModule = await import("/src/store/index.js");
    const uiStateModule = await import("/src/store/uiStateSlice.js");
    storeModule.default.dispatch(uiStateModule.setSelectedCity(city));
  }, presets[name]);
  await page.waitForSelector(`text=${expectedText}`, { timeout: 30000 });
  await settle(page);
}

async function screenshot(page, name, options = {}) {
  await settle(page);
  try {
    await page.screenshot({ path: name, fullPage: Boolean(options.fullPage), timeout: 60000, animations: "disabled" });
  } catch (error) {
    console.warn(`screenshot fallback for ${name}: ${error.message}`);
    await page.screenshot({ path: name, fullPage: false, timeout: 60000, animations: "disabled" });
  }
}

async function navigateSpa(page, href) {
  await page.locator(`a[href='${href}']`).first().click();
  await page.waitForURL(new RegExp(`${href.replace(/\//g, "\\/")}$`), { timeout: 20000 });
  await settle(page);
}

async function runReplay(page, stationKey, date, time, screenshotName) {
  await navigateSpa(page, "/gov/forecast");
  await page.waitForSelector("text=Historical Forecast Replay", { timeout: 20000 });
  await page.locator(".uqi-replay-controls select").first().selectOption(stationKey);
  await page.locator(".uqi-replay-controls input[type='date']").fill(date);
  await page.locator(".uqi-replay-controls input[type='time']").fill(time);
  await page.getByRole("button", { name: /Run Historical Forecast/i }).click();
  await page.waitForSelector("text=Replay complete", { timeout: 20000 });
  await screenshot(page, screenshotName, { fullPage: true });
}

async function main() {
  const browser = await chromium.launch({
    headless: true,
    executablePath: chromePath,
  });

  const context = await browser.newContext({ viewport: { width: 1440, height: 1100 } });
  const page = await context.newPage();
  wireDiagnostics(page);
  await login(page);
  await selectCity(page, "Lucknow", "Lucknow, Uttar Pradesh, India");

  await screenshot(page, "final-authenticated-overview.png", { fullPage: true });
  await screenshot(page, "final-overview-desktop.png", { fullPage: true });

  await page.setViewportSize({ width: 390, height: 920 });
  await screenshot(page, "final-overview-mobile.png", { fullPage: true });
  await page.setViewportSize({ width: 1440, height: 1100 });

  await runReplay(page, "gomti_nagar_lucknow_uppcb", "2025-07-10", "18:30", "final-historical-replay-gomti.png");
  await runReplay(page, "ito_delhi_cpcb", "2025-05-26", "00:30", "final-historical-replay-ito.png");
  await runReplay(page, "bandra_kurla_complex_mumbai_mpcb", "2025-07-10", "18:30", "final-historical-replay-bkc.png");
  await runReplay(page, "gomti_nagar_lucknow_uppcb", "2025-07-16", "19:30", "final-historical-replay-missing-actual.png");

  await navigateSpa(page, "/gov/source-analysis");
  await page.waitForSelector("text=Source Attribution", { timeout: 20000 });

  await navigateSpa(page, "/gov");
  await selectCity(page, "Delhi", "Delhi, India");
  await selectCity(page, "Mumbai", "Mumbai, Maharashtra, India");
  await selectCity(page, "Lucknow", "Lucknow, Uttar Pradesh, India");

  await navigateSpa(page, "/gov/maps");
  await selectCity(page, "Lucknow", "Lucknow, Uttar Pradesh, India");
  await page.waitForSelector(".leaflet-container", { state: "attached", timeout: 20000 });
  await page.waitForSelector("text=Map layers", { timeout: 20000 }).catch(() => {});
  await screenshot(page, "final-map-leaflet.png", { fullPage: true });
  await page.getByRole("button", { name: /Refresh layers/i }).click().catch(() => {});
  await screenshot(page, "final-map-degraded-layer.png", { fullPage: true });

  await navigateSpa(page, "/gov/enforcement");
  await selectCity(page, "Lucknow", "Lucknow, Uttar Pradesh, India");
  await page.waitForSelector("text=Recommended Actions", { timeout: 20000 });
  await screenshot(page, "final-enforcement.png", { fullPage: true });

  await navigateSpa(page, "/gov/health-advisory");
  await selectCity(page, "Lucknow", "Lucknow, Uttar Pradesh, India");
  await page.waitForSelector("text=Citizen-facing health guidance", { timeout: 20000 });
  await screenshot(page, "final-health-advisory.png", { fullPage: true });

  await navigateSpa(page, "/gov");
  await page.getByRole("button", { name: /Decision Copilot/i }).click();
  await page.waitForSelector("text=Decision Copilot", { timeout: 20000 });
  await screenshot(page, "final-copilot-open.png", { fullPage: true });
  await page.getByRole("button", { name: /^Close$/i }).click();
  await screenshot(page, "final-copilot-closed.png", { fullPage: true });

  const errorContext = await browser.newContext({ viewport: { width: 1440, height: 1100 } });
  const errorPage = await errorContext.newPage();
  wireDiagnostics(errorPage);
  await errorPage.route("**/api/v1/intelligence/decision**", (route) => route.abort("failed"));
  await login(errorPage);
  await errorPage.waitForSelector("text=Unable to load", { timeout: 20000 }).catch(() => {});
  await screenshot(errorPage, "final-api-error-state.png", { fullPage: true });
  await errorContext.close();

  await context.close();
  await browser.close();

  const flagged = consoleMessages.filter(({ text }) => (
    /BillingNotEnabledMapError|Google Maps|Map container is already initialized|Each child in a list should have a unique|React error|Minified React error/i.test(text)
  ));
  const errors = consoleMessages.filter(({ type }) => type === "error");
  console.log(JSON.stringify({
    screenshots,
    consoleMessageCount: consoleMessages.length,
    consoleErrorCount: errors.length,
    pageErrorCount: pageErrors.length,
    consoleErrors: errors,
    flaggedMessages: flagged,
    pageErrors,
  }, null, 2));
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
