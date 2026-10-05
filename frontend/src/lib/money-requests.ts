import { ApiError } from "./transfers";

export type RequestStatus = "PENDING" | "PAID" | "DECLINED" | "CANCELLED";
export type RequestAction = "pay" | "decline" | "cancel";
export type MoneyRequest = {
  requestId: string;
  requesterAccountId: string;
  payerAccountId: string;
  requesterDisplayName: string;
  payerDisplayName: string;
  amountInCents: number;
  currency: "USD";
  reason: string | null;
  status: RequestStatus;
  transferId: string | null;
  createdAt: string;
  resolvedAt: string | null;
};
export type RequestIntent = {
  businessId: string;
  key: string;
  action: "create" | RequestAction;
  requestId?: string;
  amountInCents: number;
  counterpartyName: string;
  payload?: { payerAccountId: string; amountInCents: number; reason?: string };
};
export const requestStorageKey = "brex-pending-money-request-v1";

export function requestMutation(intent: RequestIntent): {
  path: string;
  options: RequestInit;
} {
  const headers: Record<string, string> = { "Idempotency-Key": intent.key };
  if (intent.action === "create") {
    if (!intent.payload) throw new Error("Missing request details.");
    headers["Content-Type"] = "application/json";
    return {
      path: "/api/money-requests",
      options: {
        method: "POST",
        headers,
        body: JSON.stringify(intent.payload),
      },
    };
  }
  if (!intent.requestId) throw new Error("Missing request reference.");
  return {
    path: `/api/money-requests/${encodeURIComponent(intent.requestId)}/${intent.action}`,
    options: { method: "POST", headers },
  };
}

export function isDefinitiveRequestFailure(error: unknown): error is ApiError {
  return (
    error instanceof ApiError &&
    error.status >= 400 &&
    error.status < 500 &&
    error.status !== 408 &&
    error.status !== 429 &&
    error.code !== "IDEMPOTENCY_CONFLICT"
  );
}

export function allowedRequestActions(
  item: MoneyRequest,
  accountId: string,
): RequestAction[] {
  if (item.status !== "PENDING") return [];
  if (item.payerAccountId === accountId) return ["pay", "decline"];
  if (item.requesterAccountId === accountId) return ["cancel"];
  return [];
}
