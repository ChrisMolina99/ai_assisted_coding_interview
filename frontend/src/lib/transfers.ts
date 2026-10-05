export type Business = { id: string; displayName: string };
export type Account = {
  id: string;
  businessId: string;
  displayName: string;
  currency: string;
  balanceInCents: number;
};
export type Recipient = Omit<Account, "balanceInCents">;
export type Transfer = {
  transferId: string;
  senderAccountId: string;
  recipientAccountId: string;
  senderDisplayName: string;
  recipientDisplayName: string;
  amountInCents: number;
  currency: string;
  reason: string | null;
  status: "COMPLETED";
  createdAt: string;
};
export type Page<T> = { items: T[]; nextCursor: string | null };
export type Intent = {
  businessId: string;
  key: string;
  payload: {
    recipientAccountId: string;
    amountInCents: number;
    reason?: string;
  };
};

export function parseAmount(value: string): number | null {
  const match = /^(\d+)(?:\.(\d{1,2}))?$/.exec(value.trim());
  if (!match) return null;
  const cents =
    Number(match[1]) * 100 + Number((match[2] ?? "").padEnd(2, "0"));
  return Number.isSafeInteger(cents) && cents >= 1 && cents <= 1_000_000_000
    ? cents
    : null;
}

export const money = (cents: number) =>
  new Intl.NumberFormat("en-US", { style: "currency", currency: "USD" }).format(
    cents / 100,
  );

export function historyQuery(
  start: string,
  end: string,
  cursor?: string,
): string {
  const query = new URLSearchParams({ limit: "20" });
  if (start)
    query.set("startDate", new Date(`${start}T00:00:00.000Z`).toISOString());
  if (end) {
    const exclusive = new Date(`${end}T00:00:00.000Z`);
    exclusive.setUTCDate(exclusive.getUTCDate() + 1);
    query.set("endDate", exclusive.toISOString());
  }
  if (cursor) query.set("cursor", cursor);
  return query.toString();
}

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
    public code = "",
    public fieldErrors: Record<string, string> = {},
  ) {
    super(message);
  }
}

export async function api<T>(
  path: string,
  businessId?: string,
  options: RequestInit = {},
): Promise<T> {
  const timeout = new AbortController();
  const timer = setTimeout(() => timeout.abort(), 15000);
  const signal = options.signal
    ? AbortSignal.any([options.signal, timeout.signal])
    : timeout.signal;
  try {
    const response = await fetch(
      `${process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080"}${path}`,
      {
        ...options,
        signal,
        headers: {
          ...(businessId ? { "X-Business-Id": businessId } : {}),
          ...options.headers,
        },
      },
    );
    if (!response.ok) {
      const error = (await response.json().catch(() => null)) as {
        message?: string;
        code?: string;
        fieldErrors?: Record<string, string>;
      } | null;
      throw new ApiError(
        response.status,
        error?.message ?? `Request failed (${response.status}).`,
        error?.code,
        error?.fieldErrors,
      );
    }
    return (await response.json()) as T;
  } finally {
    clearTimeout(timer);
  }
}
