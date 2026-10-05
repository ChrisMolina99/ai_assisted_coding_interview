import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import ts from "typescript";

// Exercise the shipped TypeScript helpers without adding a test dependency.
const source = await readFile(
  new URL("../src/lib/transfers.ts", import.meta.url),
  "utf8",
);
const compiled = ts.transpileModule(source, {
  compilerOptions: {
    target: ts.ScriptTarget.ES2022,
    module: ts.ModuleKind.ESNext,
  },
}).outputText;
const { parseAmount, historyQuery, api, ApiError } = await import(
  `data:text/javascript;base64,${Buffer.from(compiled).toString("base64")}`
);

test("amounts use exact cents and honor limits", () => {
  for (const [input, expected] of [
    ["0.01", 1],
    ["0.29", 29],
    ["1.1", 110],
    ["001.01", 101],
    [" 12.34 ", 1234],
    ["10000000.00", 1000000000],
  ]) {
    assert.equal(parseAmount(input), expected);
  }
  for (const input of [
    "",
    "0",
    "0.00",
    "-1",
    "1.001",
    "1e2",
    "NaN",
    "Infinity",
    "1,000",
    "10000000.01",
    "900719925474099100",
    ".5",
  ]) {
    assert.equal(parseAmount(input), null, input);
  }
});

test("date range includes complete UTC days across month and year boundaries", () => {
  const query = new URLSearchParams(
    historyQuery("2026-12-31", "2026-12-31", "a+b/="),
  );
  assert.equal(query.get("startDate"), "2026-12-31T00:00:00.000Z");
  assert.equal(query.get("endDate"), "2027-01-01T00:00:00.000Z");
  assert.equal(query.get("cursor"), "a+b/=");
  assert.equal(
    new URLSearchParams(historyQuery("", "2028-02-28")).get("endDate"),
    "2028-02-29T00:00:00.000Z",
  );
  assert.equal(
    new URLSearchParams(historyQuery("", "")).has("startDate"),
    false,
  );
});

test("REST requests retain business identity, idempotency headers, and structured errors", async () => {
  const originalFetch = globalThis.fetch;
  try {
    globalThis.fetch = async (url, options) => {
      assert.match(url, /\/api\/transfers$/);
      assert.equal(options.headers["X-Business-Id"], "business-1");
      assert.equal(options.headers["Idempotency-Key"], "same-key");
      assert.equal(options.body, '{"amountInCents":29}');
      return new Response(
        JSON.stringify({
          code: "INSUFFICIENT_FUNDS",
          message: "Insufficient funds",
          fieldErrors: {},
        }),
        { status: 409 },
      );
    };
    await assert.rejects(
      api("/api/transfers", "business-1", {
        method: "POST",
        headers: { "Idempotency-Key": "same-key" },
        body: '{"amountInCents":29}',
      }),
      (error) =>
        error instanceof ApiError &&
        error.status === 409 &&
        error.code === "INSUFFICIENT_FUNDS",
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
});
