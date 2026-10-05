import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import ts from "typescript";

const compile = (source) =>
  ts.transpileModule(source, {
    compilerOptions: {
      target: ts.ScriptTarget.ES2022,
      module: ts.ModuleKind.ESNext,
    },
  }).outputText;
const dataUrl = (source) =>
  `data:text/javascript;base64,${Buffer.from(source).toString("base64")}`;
const transferUrl = dataUrl(
  compile(
    await readFile(new URL("../src/lib/transfers.ts", import.meta.url), "utf8"),
  ),
);
const requestSource = compile(
  await readFile(
    new URL("../src/lib/money-requests.ts", import.meta.url),
    "utf8",
  ),
);
const { allowedRequestActions, requestMutation, isDefinitiveRequestFailure } =
  await import(
    dataUrl(requestSource.replace('"./transfers"', JSON.stringify(transferUrl)))
  );
const { ApiError } = await import(transferUrl);

const item = {
  requestId: "request-1",
  requesterAccountId: "requester",
  payerAccountId: "payer",
  amountInCents: 29,
  status: "PENDING",
};

test("only the appropriate participant can resolve a pending request", () => {
  assert.deepEqual(allowedRequestActions(item, "payer"), ["pay", "decline"]);
  assert.deepEqual(allowedRequestActions(item, "requester"), ["cancel"]);
  assert.deepEqual(allowedRequestActions(item, "other"), []);
  for (const status of ["PAID", "DECLINED", "CANCELLED"]) {
    assert.deepEqual(allowedRequestActions({ ...item, status }, "payer"), []);
    assert.deepEqual(
      allowedRequestActions({ ...item, status }, "requester"),
      [],
    );
  }
});

test("restored creation intents preserve exact body and idempotency key", () => {
  const saved = JSON.stringify({
    businessId: "business",
    key: "original-key",
    action: "create",
    amountInCents: 29,
    counterpartyName: "Payer",
    payload: { payerAccountId: "payer", amountInCents: 29, reason: "Service" },
  });
  const first = requestMutation(JSON.parse(saved));
  const retried = requestMutation(JSON.parse(saved));
  assert.deepEqual(first, retried);
  assert.equal(first.path, "/api/money-requests");
  assert.equal(first.options.headers["Idempotency-Key"], "original-key");
  assert.equal(
    first.options.body,
    '{"payerAccountId":"payer","amountInCents":29,"reason":"Service"}',
  );
});

test("action retries retain their endpoint and key without inventing a new payment", () => {
  for (const action of ["pay", "decline", "cancel"]) {
    const intent = JSON.parse(
      JSON.stringify({
        businessId: "business",
        key: "stable-key",
        action,
        requestId: "request-1",
        amountInCents: 29,
        counterpartyName: "Other",
      }),
    );
    const mutation = requestMutation(intent);
    assert.equal(mutation.path, `/api/money-requests/request-1/${action}`);
    assert.equal(mutation.options.headers["Idempotency-Key"], "stable-key");
    assert.equal(mutation.options.body, undefined);
    assert.deepEqual(requestMutation(intent), mutation);
  }
});

test("unknown outcomes retain intent while definitive rejections allow a corrected new intent", () => {
  for (const error of [
    new TypeError("Network failure"),
    new ApiError(503, "Busy"),
    new ApiError(500, "Failure"),
    new ApiError(408, "Timeout"),
    new ApiError(429, "Rate limited"),
    new ApiError(409, "Conflict", "IDEMPOTENCY_CONFLICT"),
  ]) {
    assert.equal(isDefinitiveRequestFailure(error), false);
  }
  for (const [status, code] of [
    [400, "INVALID_AMOUNT"],
    [404, "NOT_FOUND"],
    [403, "FORBIDDEN_ACTION"],
    [409, "REQUEST_NOT_PENDING"],
    [409, "INSUFFICIENT_FUNDS"],
  ]) {
    assert.equal(
      isDefinitiveRequestFailure(new ApiError(status, "Rejected", code)),
      true,
    );
  }
});
