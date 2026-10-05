"use client";

import { useEffect, useRef, useState } from "react";
import { api, money, parseAmount } from "~/lib/transfers";
import type { Account, Page, Recipient } from "~/lib/transfers";
import {
  allowedRequestActions,
  isDefinitiveRequestFailure,
  requestMutation,
  requestStorageKey,
} from "~/lib/money-requests";
import type {
  MoneyRequest,
  RequestAction,
  RequestIntent,
  RequestStatus,
} from "~/lib/money-requests";

const actionLabels: Record<RequestAction, string> = {
  pay: "Pay",
  decline: "Decline",
  cancel: "Cancel",
};
const errorMessage = (error: unknown) =>
  error instanceof Error
    ? error.message
    : "Something went wrong. Please retry.";

type Props = {
  businessId: string;
  account: Account | null;
  recipients: Recipient[];
  accountLoading: boolean;
  refresh: number;
  locked: boolean;
  pending: RequestIntent | null;
  setPending: (intent: RequestIntent | null) => void;
  acquireMutation: () => boolean;
  releaseMutation: () => void;
  onResolved: (paid: boolean) => void;
};

export default function MoneyRequests({
  businessId,
  account,
  recipients,
  accountLoading,
  refresh,
  locked,
  pending,
  setPending,
  acquireMutation,
  releaseMutation,
  onResolved,
}: Props) {
  const [payerId, setPayerId] = useState("");
  const [amount, setAmount] = useState("");
  const [reason, setReason] = useState("");
  const [review, setReview] = useState(false);
  const [formError, setFormError] = useState("");
  const [feedback, setFeedback] = useState("");
  const [paidReference, setPaidReference] = useState<string | null>(null);
  const [direction, setDirection] = useState<"incoming" | "outgoing">(
    "incoming",
  );
  const [status, setStatus] = useState<RequestStatus | "">("");
  const [items, setItems] = useState<MoneyRequest[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [listError, setListError] = useState("");
  const [listRefresh, setListRefresh] = useState(0);
  const [busy, setBusy] = useState(false);
  const [confirmation, setConfirmation] = useState<{
    item: MoneyRequest;
    action: RequestAction;
  } | null>(null);
  const generation = useRef(0);
  const listLock = useRef(false);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  useEffect(() => {
    setPayerId("");
    setAmount("");
    setReason("");
    setReview(false);
    setConfirmation(null);
    setFormError("");
    setFeedback("");
    setPaidReference(null);
  }, [businessId]);

  useEffect(() => {
    setConfirmation(null);
  }, [direction, status]);

  useEffect(() => {
    if (!businessId) return;
    const controller = new AbortController();
    const current = ++generation.current;
    listLock.current = false;
    setItems([]);
    setNextCursor(null);
    setLoading(true);
    setListError("");
    const query = new URLSearchParams({ direction, limit: "20" });
    if (status) query.set("status", status);
    api<Page<MoneyRequest>>(`/api/money-requests?${query}`, businessId, {
      signal: controller.signal,
    })
      .then((data) => {
        if (!controller.signal.aborted && current === generation.current) {
          setItems(data.items);
          setNextCursor(data.nextCursor);
        }
      })
      .catch((error) => {
        if (!controller.signal.aborted && current === generation.current)
          setListError(errorMessage(error));
      })
      .finally(() => {
        if (!controller.signal.aborted && current === generation.current)
          setLoading(false);
      });
    return () => controller.abort();
  }, [businessId, direction, status, refresh, listRefresh]);

  async function loadMore() {
    if (!nextCursor || listLock.current) return;
    listLock.current = true;
    const current = generation.current;
    setLoading(true);
    setListError("");
    const query = new URLSearchParams({
      direction,
      limit: "20",
      cursor: nextCursor,
    });
    if (status) query.set("status", status);
    try {
      const page = await api<Page<MoneyRequest>>(
        `/api/money-requests?${query}`,
        businessId,
      );
      if (current === generation.current && mounted.current) {
        setItems((previous) => [...previous, ...page.items]);
        setNextCursor(page.nextCursor);
      }
    } catch (error) {
      if (current === generation.current && mounted.current)
        setListError(errorMessage(error));
    } finally {
      if (current === generation.current && mounted.current) {
        listLock.current = false;
        setLoading(false);
      }
    }
  }

  function reviewRequest(event: React.FormEvent) {
    event.preventDefault();
    if (locked || pending) return;
    setFormError("");
    setFeedback("");
    setPaidReference(null);
    if (
      !account ||
      !recipients.some((item) => item.id === payerId) ||
      payerId === account.id
    ) {
      setFormError("Choose another business as the payer.");
      return;
    }
    if (parseAmount(amount) === null) {
      setFormError(
        "Enter an amount from $0.01 to $10,000,000.00, with at most two decimal places.",
      );
      return;
    }
    if (reason.length > 280) {
      setFormError("Keep the reason to 280 characters or fewer.");
      return;
    }
    setReview(true);
    setConfirmation(null);
  }

  function clearStoredIntent() {
    try {
      sessionStorage.removeItem(requestStorageKey);
    } catch {
      /* The server response remains authoritative; a stale stored key replays safely. */
    }
  }

  async function mutate(intent: RequestIntent) {
    if (locked || !acquireMutation()) return;
    try {
      sessionStorage.setItem(requestStorageKey, JSON.stringify(intent));
    } catch {
      releaseMutation();
      setFormError(
        "Your browser cannot save retry information. Enable session storage before submitting.",
      );
      return;
    }
    setBusy(true);
    setPending(intent);
    setFormError("");
    setFeedback("");
    setPaidReference(null);
    try {
      const mutation = requestMutation(intent);
      const result = await api<MoneyRequest>(
        mutation.path,
        intent.businessId,
        mutation.options,
      );
      clearStoredIntent();
      if (!mounted.current) return;
      setPending(null);
      setConfirmation(null);
      setReview(false);
      if (intent.action === "create") {
        setPayerId("");
        setAmount("");
        setReason("");
        setDirection("outgoing");
        setStatus("");
        setFeedback(
          `Request sent to ${result.payerDisplayName} for ${money(result.amountInCents)}. No funds have moved.`,
        );
      } else if (intent.action === "pay") {
        setFeedback(
          `Request paid: ${money(result.amountInCents)} sent to ${result.requesterDisplayName}.`,
        );
        setPaidReference(result.transferId);
      } else {
        setFeedback(
          intent.action === "decline"
            ? "Request declined. No funds have moved."
            : "Request cancelled. No funds have moved.",
        );
      }
      setListRefresh((value) => value + 1);
      onResolved(intent.action === "pay");
    } catch (error) {
      if (!mounted.current) return;
      if (isDefinitiveRequestFailure(error)) {
        clearStoredIntent();
        setPending(null);
        setConfirmation(null);
        setReview(false);
        const fields = Object.values(error.fieldErrors).join(" ");
        if (error.code === "REQUEST_NOT_PENDING")
          setFormError(
            "This request has already been resolved. The list has been refreshed; no additional payment was made.",
          );
        else if (error.code === "INSUFFICIENT_FUNDS")
          setFormError(
            "Your account has insufficient funds. The request remains pending; you can try paying again when funds are available.",
          );
        else setFormError(`${error.message}${fields ? ` ${fields}` : ""}`);
        setListRefresh((value) => value + 1);
        if (
          error.code === "INSUFFICIENT_FUNDS" ||
          error.code === "REQUEST_NOT_PENDING"
        )
          onResolved(true);
      } else {
        setFormError(
          `The ${intent.action === "create" ? "request creation" : intent.action} outcome is unconfirmed. ${errorMessage(error)} Retry the same action safely; other submissions are paused.`,
        );
      }
    } finally {
      releaseMutation();
      if (mounted.current) setBusy(false);
    }
  }

  function submitCreate() {
    if (locked || pending || !review || !account) return;
    const cents = parseAmount(amount);
    if (cents === null) return;
    void mutate({
      businessId,
      key: crypto.randomUUID(),
      action: "create",
      amountInCents: cents,
      counterpartyName:
        recipients.find((item) => item.id === payerId)?.displayName ?? payerId,
      payload: {
        payerAccountId: payerId,
        amountInCents: cents,
        ...(reason.trim() ? { reason: reason.trim() } : {}),
      },
    });
  }

  function submitAction() {
    if (!confirmation || locked || pending) return;
    const { item, action } = confirmation;
    void mutate({
      businessId,
      key: crypto.randomUUID(),
      action,
      requestId: item.requestId,
      amountInCents: item.amountInCents,
      counterpartyName:
        action === "cancel" ? item.payerDisplayName : item.requesterDisplayName,
    });
  }

  const disabled = locked || !!pending || busy;
  const payer = recipients.find((item) => item.id === payerId);
  return (
    <section className="requests-workspace" aria-labelledby="requests-title">
      <h2 id="requests-title">Request money</h2>
      <p className="muted">
        Ask another business to pay. Requests do not reserve funds; money moves
        only when the payer confirms payment.
      </p>
      {locked && (
        <p className="notice">
          Resolve your pending transfer before creating or acting on money
          requests.
        </p>
      )}
      {formError && (
        <div className="notice error" role="alert">
          {formError}
        </div>
      )}
      {feedback && (
        <div className="notice success" role="status">
          <p>{feedback}</p>
          {paidReference && (
            <p className="reference">Transfer reference: {paidReference}</p>
          )}
        </div>
      )}
      {pending && (
        <div className="review" role="status">
          <h3>
            {busy
              ? "Submitting request action…"
              : "Confirm your previous request action"}
          </h3>
          <p>
            {pending.action === "create"
              ? "Request"
              : actionLabels[pending.action]}{" "}
            {money(pending.amountInCents)} — {pending.counterpartyName}
          </p>
          <p className="muted">
            This action has an unconfirmed outcome. Business selection and other
            submissions are paused until it is resolved. Retrying uses the same
            action reference.
          </p>
          <button
            className="primary"
            disabled={busy || locked}
            onClick={() => void mutate(pending)}
          >
            {busy ? "Please wait…" : "Retry same request action safely"}
          </button>
        </div>
      )}
      <div className="content-grid">
        <section className="panel" aria-labelledby="create-request-title">
          <h3 id="create-request-title">Create a money request</h3>
          <form onSubmit={reviewRequest}>
            <fieldset
              disabled={
                disabled ||
                !account ||
                accountLoading ||
                recipients.length === 0 ||
                review
              }
            >
              <label htmlFor="request-payer">Payer</label>
              <select
                id="request-payer"
                value={payerId}
                required
                onChange={(event) => setPayerId(event.target.value)}
              >
                <option value="">Select a business</option>
                {recipients.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.displayName}
                  </option>
                ))}
              </select>
              {account && !accountLoading && recipients.length === 0 && (
                <p>No other business accounts are available.</p>
              )}
              <label htmlFor="request-amount">Requested amount (USD)</label>
              <input
                id="request-amount"
                value={amount}
                inputMode="decimal"
                placeholder="0.00"
                required
                onChange={(event) => setAmount(event.target.value)}
                aria-describedby="request-amount-help"
              />
              <p className="muted" id="request-amount-help">
                $0.01–$10,000,000.00; up to two decimal places.
              </p>
              <label htmlFor="request-reason">
                Request reason <span className="muted">(optional)</span>
              </label>
              <textarea
                id="request-reason"
                value={reason}
                maxLength={280}
                rows={3}
                onChange={(event) => setReason(event.target.value)}
              />
              <p className="muted">{reason.length}/280 characters</p>
            </fieldset>
            {!review && (
              <button
                className="primary"
                type="submit"
                disabled={
                  disabled ||
                  !account ||
                  accountLoading ||
                  recipients.length === 0
                }
              >
                Review request
              </button>
            )}
            {review && (
              <div className="review" aria-label="Review money request">
                <h3>Review your money request</h3>
                <p>
                  Request <strong>{money(parseAmount(amount) ?? 0)}</strong>{" "}
                  from <strong>{payer?.displayName}</strong>.
                </p>
                {reason.trim() && <p>{reason.trim()}</p>}
                <p className="muted">
                  No money will move until the payer pays this request.
                </p>
                <div className="button-row">
                  <button
                    type="button"
                    disabled={disabled}
                    onClick={() => setReview(false)}
                  >
                    Edit request
                  </button>
                  <button
                    className="primary"
                    type="button"
                    disabled={disabled}
                    onClick={submitCreate}
                  >
                    Confirm and request
                  </button>
                </div>
              </div>
            )}
          </form>
        </section>
        <section className="panel" aria-labelledby="request-list-title">
          <h3 id="request-list-title">Money requests</h3>
          <div className="filter-form">
            <div>
              <label htmlFor="request-direction">Request direction</label>
              <select
                id="request-direction"
                value={direction}
                onChange={(event) =>
                  setDirection(event.target.value as "incoming" | "outgoing")
                }
              >
                <option value="incoming">Incoming — requests to pay</option>
                <option value="outgoing">Outgoing — requests you sent</option>
              </select>
            </div>
            <div>
              <label htmlFor="request-status">Request status</label>
              <select
                id="request-status"
                value={status}
                onChange={(event) =>
                  setStatus(event.target.value as RequestStatus | "")
                }
              >
                <option value="">All statuses</option>
                <option value="PENDING">Pending</option>
                <option value="PAID">Paid</option>
                <option value="DECLINED">Declined</option>
                <option value="CANCELLED">Cancelled</option>
              </select>
            </div>
          </div>
          {listError && (
            <div className="notice error" role="alert">
              <p>{listError}</p>
              <button
                disabled={loading}
                onClick={() =>
                  nextCursor && items.length
                    ? void loadMore()
                    : setListRefresh((value) => value + 1)
                }
              >
                Retry requests
              </button>
            </div>
          )}
          {loading && <p role="status">Loading money requests…</p>}
          {!loading && !listError && items.length === 0 && (
            <div className="empty">
              No {direction} requests
              {status ? ` with status ${status.toLowerCase()}` : " yet"}.
            </div>
          )}
          <ul className="transfer-list request-list">
            {items.map((item) => (
              <li key={item.requestId}>
                <div>
                  <strong>
                    {direction === "incoming"
                      ? `Requested by ${item.requesterDisplayName}`
                      : `Requested from ${item.payerDisplayName}`}
                  </strong>
                  <time dateTime={item.createdAt}>
                    {new Date(item.createdAt).toLocaleString("en-US", {
                      timeZone: "UTC",
                    })}{" "}
                    UTC
                  </time>
                  {item.reason && <p>{item.reason}</p>}
                  <span className="reference">
                    Request reference: {item.requestId}
                  </span>
                  {item.transferId && (
                    <span className="reference">
                      Transfer reference: {item.transferId}
                    </span>
                  )}
                  <div className="button-row request-actions">
                    {account &&
                      allowedRequestActions(item, account.id).map((action) => (
                        <button
                          key={action}
                          disabled={disabled}
                          onClick={() => {
                            setConfirmation({ item, action });
                            setReview(false);
                            setFormError("");
                          }}
                        >
                          {actionLabels[action]} request
                        </button>
                      ))}
                  </div>
                  {confirmation?.item.requestId === item.requestId && (
                    <div className="review" aria-label="Confirm request action">
                      <h3>{actionLabels[confirmation.action]} this request?</h3>
                      <p>
                        {confirmation.action === "pay"
                          ? `Send ${money(item.amountInCents)} to ${item.requesterDisplayName} immediately.`
                          : "This closes the request without moving funds."}
                      </p>
                      <div className="button-row">
                        <button
                          disabled={disabled}
                          onClick={() => setConfirmation(null)}
                        >
                          Go back
                        </button>
                        <button
                          className="primary"
                          disabled={disabled}
                          onClick={submitAction}
                        >
                          Confirm {confirmation.action}
                        </button>
                      </div>
                    </div>
                  )}
                </div>
                <div className="request-amount">
                  <strong>{money(item.amountInCents)}</strong>
                  <span
                    className={`request-status status-${item.status.toLowerCase()}`}
                  >
                    {item.status.charAt(0) + item.status.slice(1).toLowerCase()}
                  </span>
                </div>
              </li>
            ))}
          </ul>
          {nextCursor && (
            <button disabled={loading} onClick={() => void loadMore()}>
              Load more requests
            </button>
          )}
        </section>
      </div>
    </section>
  );
}
