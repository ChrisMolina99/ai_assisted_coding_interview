# HLD
- Implement B2B money movement app like Venmo.

## Functional Requirements
- User money transfer from one account to other
- User can see the transaction history.
- A business can request money from another business.
- The payer can pay or decline a pending request; the requester can cancel it.

### Validatios
- Ensure account has enough money to transfer
- Ensure other account exists for transfer
- Ensure no double spending for concurrent requests
- Reject transfers and money requests to the same account.
- Require idempotency keys for money movement and request mutations.

### Out of scope
- Bank information connection.
- Login/authorization steps
- Partial payments, request editing, expiration, reminders, and notifications.

## Approved request-money V1

One seeded USD account per business. The requester chooses another business,
enters an amount and optional reason, reviews, and sends the request. The payer
sees it in incoming requests and can review and pay or decline. The requester
sees outgoing requests and can cancel while pending. Payment confirmation links
to the completed transfer and refreshes balances and transfer history.

Creating a request does not reserve or move funds. Payment checks the payer's
current balance. Insufficient funds leaves the request pending. Requests have
immutable participants, amount, currency, and reason, with transitions from
`PENDING` to exactly one of `PAID`, `DECLINED`, or `CANCELLED`.

### Request-money REST contract

All endpoints require `X-Business-Id`; mutations also require `Idempotency-Key`.
The identity header is a demo selector, not authentication.

| Endpoint | Behavior |
| --- | --- |
| `POST /api/money-requests` | Create; body `{payerAccountId, amountInCents, reason?}`; return 201 and Location. |
| `GET /api/money-requests?direction=incoming\|outgoing&status=...&limit=20&cursor=...` | Incoming means current payer; outgoing means current requester. Default incoming; omitted status includes all. |
| `GET /api/money-requests/{id}` | Participant-scoped lookup. |
| `POST /api/money-requests/{id}/pay` | Payer pays pending request; return updated request, 200. |
| `POST /api/money-requests/{id}/decline` | Payer declines pending request; return updated request, 200. |
| `POST /api/money-requests/{id}/cancel` | Requester cancels pending request; return updated request, 200. |

Responses contain `requestId`, `requesterAccountId`, `payerAccountId`, both display
names, `amountInCents`, `currency`, nullable `reason`, `status`, nullable
`transferId`, `createdAt`, and nullable `resolvedAt`. Dates are UTC instants.
Lists return `{items, nextCursor}` ordered by creation time then request ID,
descending. Cursors are scoped to the business, direction, and status filters.

Amounts are integer JSON cents from 1 to 1,000,000,000; reasons have at most 280
characters. Invalid input returns 400; inaccessible requests return 404; wrong
participant roles return 403; terminal-state conflicts and insufficient funds
return 409; self-requests return 422. Errors use `{code, message, fieldErrors}`.

### Schema and transaction design

- `money_requests`: UUID primary key, requester and payer account foreign keys,
  immutable amount/currency/reason, status, unique nullable transfer foreign key,
  creation and resolution timestamps. Constraints reject self-requests and
  invalid amounts; indexes support incoming and outgoing history.
- Request action outcomes: actor account, action, idempotency key, input
  fingerprint, stored HTTP status/response, and creation timestamp. Unique
  actor/action/key; action fingerprints include the target request.

Creation and lifecycle actions store their business outcomes for identical-key
replay. Reusing a key with different input returns a conflict. Technical failures
roll back and may be retried with the same key. Payment locks the request first,
then accounts in deterministic order, and commits debit, credit, transfer,
request status/link, and action outcome together. Decline/cancel move no funds.

### Milestones and acceptance evidence

1. Define schema and REST types; verify constraints and input validation.
2. Implement atomic backend lifecycle and idempotency; test wrong roles, scope,
   no movement on creation/rejection, rollback, and concurrent action races.
3. Implement request form, review, incoming/outgoing lists, confirmations, status
   filters, pagination, loading/empty/error states, and shared safe-retry locking.
4. Run backend tests, frontend typecheck/build and focused tests, then independent
   API/browser verification after both implementations complete.

Acceptance: request creation changes no balances; one successful payment creates
one transfer for the requested amount; insufficient funds stays pending;
concurrent pay/pay, pay/cancel, and pay/decline produce one terminal result;
response-loss/reload retries do not duplicate movement; only participants can
view and the designated role can act; scoped pagination and prior direct
transfers work. Evidence comes from integration tests and running UI/API probes.

## Existing direct-transfer API

Scoped requests use `X-Business-Id`; transfer creation also requires
`Idempotency-Key`. Direct transfers remain compatible with request payments.

```text
POST /api/transfers
{recipientAccountId, amountInCents, reason?}
-> 201 Created, completed transfer with transferId and status COMPLETED

GET /api/transfers?startDate=...&endDate=...&limit=20&cursor=...
-> {items: Transfer[], nextCursor: string | null}
```
