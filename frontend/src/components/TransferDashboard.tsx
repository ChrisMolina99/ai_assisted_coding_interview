"use client";

import { useEffect, useRef, useState } from "react";
import MoneyRequests from "~/components/MoneyRequests";
import { requestStorageKey } from "~/lib/money-requests";
import type { RequestIntent } from "~/lib/money-requests";
import {
  api,
  ApiError,
  historyQuery,
  money,
  parseAmount,
} from "~/lib/transfers";
import type {
  Account,
  Business,
  Intent,
  Page,
  Recipient,
  Transfer,
} from "~/lib/transfers";

const storageKey = "brex-pending-transfer-v1";
const errorMessage = (error: unknown) =>
  error instanceof Error
    ? error.message
    : "Something went wrong. Please retry.";

export default function TransferDashboard() {
  const [businesses, setBusinesses] = useState<Business[]>([]);
  const [businessId, setBusinessId] = useState("");
  const [businessError, setBusinessError] = useState("");
  const [businessLoading, setBusinessLoading] = useState(true);
  const [businessRetry, setBusinessRetry] = useState(0);
  const [account, setAccount] = useState<Account | null>(null);
  const [recipients, setRecipients] = useState<Recipient[]>([]);
  const [accountLoading, setAccountLoading] = useState(false);
  const [accountError, setAccountError] = useState("");
  const [refresh, setRefresh] = useState(0);
  const [recipientId, setRecipientId] = useState("");
  const [amount, setAmount] = useState("");
  const [reason, setReason] = useState("");
  const [review, setReview] = useState(false);
  const [formError, setFormError] = useState("");
  const [pending, setPending] = useState<Intent | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [pendingRequest, setPendingRequest] = useState<RequestIntent | null>(
    null,
  );
  const submitLock = useRef(false);
  const [success, setSuccess] = useState<Transfer | null>(null);
  const [history, setHistory] = useState<Transfer[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [historyError, setHistoryError] = useState("");
  const [start, setStart] = useState("");
  const [end, setEnd] = useState("");
  const [filters, setFilters] = useState({ start: "", end: "" });
  const [filterError, setFilterError] = useState("");
  const [historyRetry, setHistoryRetry] = useState(0);
  const historyGeneration = useRef(0);
  const historyLock = useRef(false);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    try {
      const saved = sessionStorage.getItem(storageKey);
      const savedRequest = sessionStorage.getItem(requestStorageKey);
      if (!saved && savedRequest) {
        const intent = JSON.parse(savedRequest) as RequestIntent;
        if (
          typeof intent.businessId === "string" &&
          typeof intent.key === "string" &&
          ["create", "pay", "decline", "cancel"].includes(intent.action)
        ) {
          setPendingRequest(intent);
          setBusinessId(intent.businessId);
        }
      }
      if (saved) {
        const intent = JSON.parse(saved) as Intent;
        if (
          typeof intent.businessId === "string" &&
          typeof intent.key === "string" &&
          typeof intent.payload?.recipientAccountId === "string" &&
          Number.isSafeInteger(intent.payload.amountInCents)
        ) {
          setPending(intent);
          setBusinessId(intent.businessId);
          setFormError(
            "A previous submission has an unconfirmed outcome. Retry it safely to confirm the result.",
          );
        }
      }
    } catch {
      /* Storage may be unavailable; submission checks persistence before sending. */
    }
    return () => {
      mounted.current = false;
    };
  }, []);

  useEffect(() => {
    const controller = new AbortController();
    setBusinessLoading(true);
    setBusinessError("");
    api<Page<Business>>("/api/businesses", undefined, {
      signal: controller.signal,
    })
      .then((data) => {
        if (!controller.signal.aborted) {
          setBusinesses(data.items);
          setBusinessId((current) =>
            current !== "" ? current : (data.items[0]?.id ?? ""),
          );
        }
      })
      .catch((error) => {
        if (!controller.signal.aborted) setBusinessError(errorMessage(error));
      })
      .finally(() => {
        if (!controller.signal.aborted) setBusinessLoading(false);
      });
    return () => controller.abort();
  }, [businessRetry]);

  useEffect(() => {
    if (!businessId) return;
    const controller = new AbortController();
    setAccountLoading(true);
    setAccountError("");
    setAccount(null);
    setRecipients([]);
    async function load() {
      const [current, first] = await Promise.all([
        api<Account>("/api/accounts/me", businessId, {
          signal: controller.signal,
        }),
        api<Page<Recipient>>("/api/accounts/recipients?limit=20", businessId, {
          signal: controller.signal,
        }),
      ]);
      const all = [...first.items];
      let cursor = first.nextCursor;
      while (cursor) {
        const page = await api<Page<Recipient>>(
          `/api/accounts/recipients?limit=20&cursor=${encodeURIComponent(cursor)}`,
          businessId,
          { signal: controller.signal },
        );
        all.push(...page.items);
        cursor = page.nextCursor;
      }
      if (!controller.signal.aborted) {
        setAccount(current);
        setRecipients(all.filter((item) => item.id !== current.id));
      }
    }
    void load()
      .catch((error) => {
        if (!controller.signal.aborted) setAccountError(errorMessage(error));
      })
      .finally(() => {
        if (!controller.signal.aborted) setAccountLoading(false);
      });
    return () => controller.abort();
  }, [businessId, refresh]);

  useEffect(() => {
    if (!businessId) return;
    const controller = new AbortController();
    const generation = ++historyGeneration.current;
    historyLock.current = false;
    setHistory([]);
    setNextCursor(null);
    setHistoryLoading(true);
    setHistoryError("");
    api<Page<Transfer>>(
      `/api/transfers?${historyQuery(filters.start, filters.end)}`,
      businessId,
      { signal: controller.signal },
    )
      .then((data) => {
        if (generation === historyGeneration.current) {
          setHistory(data.items);
          setNextCursor(data.nextCursor);
        }
      })
      .catch((error) => {
        if (
          !controller.signal.aborted &&
          generation === historyGeneration.current
        )
          setHistoryError(errorMessage(error));
      })
      .finally(() => {
        if (
          !controller.signal.aborted &&
          generation === historyGeneration.current
        )
          setHistoryLoading(false);
      });
    return () => {
      controller.abort();
    };
  }, [businessId, filters, refresh, historyRetry]);

  async function loadMore() {
    if (!nextCursor || historyLock.current) return;
    historyLock.current = true;
    const generation = historyGeneration.current;
    setHistoryLoading(true);
    setHistoryError("");
    try {
      const data = await api<Page<Transfer>>(
        `/api/transfers?${historyQuery(filters.start, filters.end, nextCursor)}`,
        businessId,
      );
      if (generation === historyGeneration.current) {
        setHistory((current) => [...current, ...data.items]);
        setNextCursor(data.nextCursor);
      }
    } catch (error) {
      if (generation === historyGeneration.current)
        setHistoryError(errorMessage(error));
    } finally {
      if (generation === historyGeneration.current) {
        historyLock.current = false;
        setHistoryLoading(false);
      }
    }
  }

  function changeBusiness(id: string) {
    if (pending || pendingRequest || submitLock.current) return;
    setBusinessId(id);
    setRecipientId("");
    setAmount("");
    setReason("");
    setReview(false);
    setFormError("");
    setSuccess(null);
  }

  function reviewTransfer(event: React.FormEvent) {
    event.preventDefault();
    if (pendingRequest || submitLock.current) return;
    setFormError("");
    setSuccess(null);
    const cents = parseAmount(amount);
    if (
      !recipients.some((item) => item.id === recipientId) ||
      recipientId === account?.id
    ) {
      setFormError("Choose another business account as the recipient.");
      return;
    }
    if (cents === null) {
      setFormError(
        "Enter an amount from $0.01 to $10,000,000.00, with at most two decimal places.",
      );
      return;
    }
    if (!account || cents > account.balanceInCents) {
      setFormError("Your account has insufficient funds for this transfer.");
      return;
    }
    if (reason.length > 280) {
      setFormError("Keep the reason to 280 characters or fewer.");
      return;
    }
    setReview(true);
  }

  async function submitTransfer() {
    if (submitLock.current || pendingRequest) return;
    const cents = parseAmount(amount);
    if (!pending && (!review || cents === null || !account)) return;
    const intent = pending ?? {
      businessId,
      key: crypto.randomUUID(),
      payload: {
        recipientAccountId: recipientId,
        amountInCents: cents!,
        ...(reason.trim() ? { reason: reason.trim() } : {}),
      },
    };
    // Persist before sending so refreshes retain the same intent and key.
    try {
      sessionStorage.setItem(storageKey, JSON.stringify(intent));
    } catch {
      setFormError(
        "Your browser cannot save retry information. Enable session storage before submitting.",
      );
      return;
    }
    submitLock.current = true;
    setSubmitting(true);
    setPending(intent);
    setFormError("");
    try {
      const transfer = await api<Transfer>(
        "/api/transfers",
        intent.businessId,
        {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "Idempotency-Key": intent.key,
          },
          body: JSON.stringify(intent.payload),
        },
      );
      try {
        sessionStorage.removeItem(storageKey);
      } catch {
        /* The server response remains authoritative if storage becomes unavailable. */
      }
      if (mounted.current) {
        setPending(null);
        setReview(false);
        setSuccess(transfer);
        setRecipientId("");
        setAmount("");
        setReason("");
        setRefresh((value) => value + 1);
      }
    } catch (error) {
      if (!mounted.current) return;
      if (
        error instanceof ApiError &&
        error.status >= 400 &&
        error.status < 500 &&
        error.status !== 408 &&
        error.status !== 429 &&
        error.code !== "IDEMPOTENCY_CONFLICT"
      ) {
        try {
          sessionStorage.removeItem(storageKey);
        } catch {
          /* The server response remains authoritative if storage becomes unavailable. */
        }
        setPending(null);
        setReview(false);
        const fields = Object.values(error.fieldErrors).join(" ");
        setFormError(`${error.message}${fields ? ` ${fields}` : ""}`);
        if (error.code === "INSUFFICIENT_FUNDS")
          setRefresh((value) => value + 1);
      } else {
        setFormError(
          `The transfer outcome is unconfirmed. ${error instanceof ApiError ? error.message : "The connection was interrupted or timed out."} Retry the same transfer safely; do not submit a new payment.`,
        );
      }
    } finally {
      submitLock.current = false;
      if (mounted.current) setSubmitting(false);
    }
  }

  const recipient = recipients.find((item) => item.id === recipientId);
  return (
    <main className="transfer-app">
      <header className="app-header">
        <div>
          <p className="eyebrow">BUSINESS PAYMENTS · DEMO</p>
          <h1>Move money with confidence</h1>
          <p>Instant transfers between business accounts in USD.</p>
        </div>
        <span className="demo-badge">Demo funds</span>
      </header>
      <section className="panel business-panel" aria-label="Current business">
        <label htmlFor="business">Your business</label>
        {businessLoading ? (
          <p role="status">Loading businesses…</p>
        ) : businessError ? (
          <div role="alert">
            <p>{businessError}</p>
            <button onClick={() => setBusinessRetry((value) => value + 1)}>
              Retry businesses
            </button>
          </div>
        ) : businesses.length === 0 ? (
          <p>No demo businesses are available.</p>
        ) : (
          <select
            id="business"
            value={businessId}
            disabled={!!pending || !!pendingRequest || submitting}
            onChange={(event) => changeBusiness(event.target.value)}
          >
            {businesses.map((item) => (
              <option key={item.id} value={item.id}>
                {item.displayName}
              </option>
            ))}
          </select>
        )}
        <p className="muted">
          Business selection is for this demo. Balances reset when the server
          restarts.
        </p>
        {accountLoading ? (
          <p role="status">Loading account…</p>
        ) : accountError ? (
          <div role="alert">
            <p>{accountError}</p>
            <button onClick={() => setRefresh((value) => value + 1)}>
              Retry account
            </button>
          </div>
        ) : (
          account && (
            <div className="balance">
              <span>Available balance</span>
              <strong>{money(account.balanceInCents)}</strong>
              <span>USD</span>
            </div>
          )
        )}
      </section>
      {success && (
        <section className="notice success" role="status">
          <h2>Transfer completed</h2>
          <p>
            {money(success.amountInCents)} sent to{" "}
            {success.recipientDisplayName}.
          </p>
          <p className="reference">Reference: {success.transferId}</p>
        </section>
      )}
      <div className="content-grid">
        <section className="panel">
          <h2>Send a transfer</h2>
          <p className="muted">
            Funds arrive immediately. Please review before sending.
          </p>
          {pendingRequest && (
            <p className="notice">
              Resolve your pending request action below before sending a
              transfer.
            </p>
          )}
          {formError && (
            <div className="notice error" role="alert">
              {formError}
            </div>
          )}
          {pending ? (
            <div className="review">
              <h3>
                {submitting
                  ? "Sending transfer…"
                  : "Confirm your previous submission"}
              </h3>
              <p>
                {money(pending.payload.amountInCents)} to{" "}
                {recipients.find(
                  (item) => item.id === pending.payload.recipientAccountId,
                )?.displayName ?? pending.payload.recipientAccountId}
              </p>
              {pending.payload.reason && <p>{pending.payload.reason}</p>}
              <p className="muted">
                Editing and business selection are paused until this transfer is
                resolved. Retrying uses the same payment reference.
              </p>
              <button
                className="primary"
                disabled={submitting}
                onClick={() => void submitTransfer()}
              >
                {submitting ? "Please wait…" : "Retry same transfer safely"}
              </button>
            </div>
          ) : (
            <form onSubmit={reviewTransfer}>
              <fieldset
                disabled={
                  !account ||
                  accountLoading ||
                  recipients.length === 0 ||
                  review ||
                  !!pendingRequest
                }
              >
                <label htmlFor="recipient">Recipient</label>
                <select
                  id="recipient"
                  value={recipientId}
                  required
                  onChange={(event) => setRecipientId(event.target.value)}
                >
                  <option value="">Select a business</option>
                  {recipients.map((item) => (
                    <option key={item.id} value={item.id}>
                      {item.displayName}
                    </option>
                  ))}
                </select>
                {!accountLoading && account && recipients.length === 0 && (
                  <p>No other business accounts are available.</p>
                )}
                <label htmlFor="amount">Amount (USD)</label>
                <input
                  id="amount"
                  inputMode="decimal"
                  placeholder="0.00"
                  value={amount}
                  required
                  onChange={(event) => setAmount(event.target.value)}
                  aria-describedby="amount-help"
                />
                <p id="amount-help" className="muted">
                  $0.01–$10,000,000.00; up to two decimal places.
                </p>
                <label htmlFor="reason">
                  Reason <span className="muted">(optional)</span>
                </label>
                <textarea
                  id="reason"
                  maxLength={280}
                  value={reason}
                  onChange={(event) => setReason(event.target.value)}
                  rows={3}
                />
                <p className="muted">{reason.length}/280 characters</p>
              </fieldset>
              {!review && (
                <button
                  className="primary"
                  disabled={
                    !account ||
                    accountLoading ||
                    recipients.length === 0 ||
                    !!pendingRequest
                  }
                  type="submit"
                >
                  Review transfer
                </button>
              )}
              {review && (
                <div className="review" aria-label="Review transfer">
                  <h3>Review your transfer</h3>
                  <p>
                    <strong>{money(parseAmount(amount) ?? 0)}</strong> to{" "}
                    <strong>{recipient?.displayName}</strong>
                  </p>
                  {reason.trim() && <p>{reason.trim()}</p>}
                  <p className="muted">
                    From {account?.displayName}. This transfer will settle
                    immediately.
                  </p>
                  <div className="button-row">
                    <button
                      type="button"
                      disabled={!!pendingRequest}
                      onClick={() => setReview(false)}
                    >
                      Edit
                    </button>
                    <button
                      className="primary"
                      disabled={!!pendingRequest}
                      type="button"
                      onClick={() => void submitTransfer()}
                    >
                      Confirm and send
                    </button>
                  </div>
                </div>
              )}
            </form>
          )}
        </section>
        <section className="panel history-panel">
          <h2>Transfer history</h2>
          <p className="muted">Completed incoming and outgoing transfers.</p>
          <form
            className="filter-form"
            onSubmit={(event) => {
              event.preventDefault();
              if (start && end && start > end) {
                setFilterError(
                  "The start date must be on or before the end date.",
                );
                return;
              }
              setFilterError("");
              setFilters({ start, end });
            }}
          >
            <div>
              <label htmlFor="start">From date</label>
              <input
                id="start"
                type="date"
                value={start}
                onChange={(event) => setStart(event.target.value)}
              />
            </div>
            <div>
              <label htmlFor="end">Through date</label>
              <input
                id="end"
                type="date"
                value={end}
                onChange={(event) => setEnd(event.target.value)}
              />
            </div>
            <button disabled={!businessId}>Apply filters</button>
          </form>
          <p className="muted">Dates include the full day in UTC.</p>
          {filterError && (
            <p className="notice error" role="alert">
              {filterError}
            </p>
          )}
          {historyError && (
            <div className="notice error" role="alert">
              <p>{historyError}</p>
              <button
                disabled={historyLoading}
                onClick={() =>
                  nextCursor && history.length
                    ? void loadMore()
                    : setHistoryRetry((value) => value + 1)
                }
              >
                Retry history
              </button>
            </div>
          )}
          {historyLoading && <p role="status">Loading transfers…</p>}
          {!historyLoading && !historyError && history.length === 0 && (
            <div className="empty">
              No completed transfers
              {filters.start || filters.end ? " in this date range" : " yet"}.
            </div>
          )}
          <ul className="transfer-list">
            {account &&
              history.map((item) => {
                const outgoing = item.senderAccountId === account.id;
                return (
                  <li key={item.transferId}>
                    <div>
                      <strong>
                        {outgoing ? "Sent to" : "Received from"}{" "}
                        {outgoing
                          ? item.recipientDisplayName
                          : item.senderDisplayName}
                      </strong>
                      <time dateTime={item.createdAt}>
                        {new Date(item.createdAt).toLocaleString("en-US", {
                          timeZone: "UTC",
                        })}{" "}
                        UTC
                      </time>
                      {item.reason && <p>{item.reason}</p>}
                      <span className="reference">
                        Reference: {item.transferId}
                      </span>
                    </div>
                    <div className={outgoing ? "outgoing" : "incoming"}>
                      <strong>
                        {outgoing ? "−" : "+"}
                        {money(item.amountInCents)}
                      </strong>
                      <span>Completed</span>
                    </div>
                  </li>
                );
              })}
          </ul>
          {nextCursor && (
            <button disabled={historyLoading} onClick={() => void loadMore()}>
              Load more transfers
            </button>
          )}
        </section>
      </div>
      <MoneyRequests
        businessId={businessId}
        account={account}
        recipients={recipients}
        accountLoading={accountLoading}
        refresh={refresh}
        locked={!!pending || submitting}
        pending={pendingRequest}
        setPending={setPendingRequest}
        acquireMutation={() => {
          if (submitLock.current) return false;
          submitLock.current = true;
          return true;
        }}
        releaseMutation={() => {
          submitLock.current = false;
        }}
        onResolved={(paid) => {
          if (paid) setRefresh((value) => value + 1);
        }}
      />
    </main>
  );
}
