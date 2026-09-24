#!/usr/bin/env node
// Headless Scittle test runner (Playwright + Chromium).
//
// Serves the repo root on an ephemeral port with caching disabled, so
// edited .cljc files are always picked up, then loads
// test/runners/test_scittle/index.html and waits for #test-output's
// data-status to become "pass" or "fail".
//
// Prerequisite (one-time): npm install && npx playwright install chromium

import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { extname, join, normalize } from "node:path";
import { chromium } from "playwright";

const ROOT = new URL("../..", import.meta.url).pathname;
const PAGE = "/test/runners/test_scittle/index.html";
const TIMEOUT_MS = 60_000;
const TYPES = { ".html": "text/html", ".js": "text/javascript" };

function startServer() {
  return new Promise((resolve) => {
    const server = createServer(async (req, res) => {
      const path = normalize(new URL(req.url, "http://localhost").pathname);
      if (path.includes("..")) {
        res.writeHead(403);
        return res.end();
      }
      try {
        const data = await readFile(join(ROOT, path));
        res.writeHead(200, {
          "Content-Type": TYPES[extname(path)] || "text/plain; charset=utf-8",
          "Cache-Control": "no-store",
        });
        res.end(data);
      } catch {
        res.writeHead(404);
        res.end("Not found");
      }
    });
    server.listen(0, "127.0.0.1", () => resolve(server));
  });
}

async function run() {
  const server = await startServer();
  let browser;
  try {
    const url = `http://127.0.0.1:${server.address().port}${PAGE}`;
    console.log(`Loading ${url}`);
    // CHROME_PATH: use an installed Chrome instead of Playwright's download
    // (CI does this; the download stalls on GitHub runners).
    browser = await chromium.launch({
      headless: true,
      ...(process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {}),
    });
    const page = await browser.newPage();
    page.on("console", (msg) => {
      if (msg.type() === "error") console.error(msg.text());
    });
    page.on("pageerror", (err) => console.error(err.message));
    await page.goto(url);

    await page.waitForFunction(
      () => ["pass", "fail"].includes(
        document.getElementById("test-output")?.dataset.status),
      null,
      { timeout: TIMEOUT_MS }
    );
    const { status, text } = await page.evaluate(() => {
      const el = document.getElementById("test-output");
      return { status: el.dataset.status, text: el.textContent };
    });
    console.log(text);
    if (status !== "pass") process.exitCode = 1;
  } catch (err) {
    console.error("Scittle test runner error:", err.message);
    process.exitCode = 1;
  } finally {
    if (browser) await browser.close();
    server.close();
  }
}

run();
