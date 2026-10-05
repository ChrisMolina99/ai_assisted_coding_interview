# Brex AI Assisted Interview Playground

## Business transfer demo

The home page provides a USD transfer demo between seeded business accounts.
Select a business, choose a recipient, review a payment, then send it and view
incoming and outgoing transfer history. Self-transfers are rejected.

Businesses can also request money from another business. Create and review a
request, then select the payer business to pay or decline it. The requester can
cancel a pending request. Creating, declining, or cancelling requests moves no
funds; payment settles the full requested amount through an atomic transfer.
Insufficient funds leaves the request pending.

Run the backend and frontend using the development commands below. The frontend
uses the backend REST API at `http://localhost:8080` by default. Demo identity is
selected in the UI and sent as `X-Business-Id`; this is not authentication.

Transfers require an `Idempotency-Key`. Retrying the same request with the same
key replays its outcome without moving funds again. Reusing a key with different
input returns a conflict. If a submission has an uncertain outcome, retry that
submission before starting another payment.

V1 supports one USD account per business and immediate internal transfers. It
does not connect banks, move real money, implement authorization, charge fees,
exchange currencies, schedule payments, or reverse transfers. The configured H2
database is in memory; restarting the backend resets balances and history.

### REST contract

`GET /api/businesses` discovers demo identities. All remaining endpoints require
the `X-Business-Id` header:

- `GET /api/accounts/me`: current account and balance in integer cents.
- `GET /api/accounts/recipients?limit=20&cursor=...`: eligible recipient accounts.
- `POST /api/transfers`: create a completed transfer; also requires
  `Idempotency-Key`. Body: `recipientAccountId`, `amountInCents`, optional `reason`.
- `GET /api/transfers/{transferId}`: a transfer involving the selected account.
- `GET /api/transfers?startDate=...&endDate=...&limit=20&cursor=...`: completed
  incoming and outgoing transfers, newest first. Dates are UTC instants, with an
  inclusive start and exclusive end.
- `POST /api/money-requests`: create a request with `payerAccountId`,
  `amountInCents`, and optional `reason`.
- `GET /api/money-requests?direction=incoming|outgoing&status=...&limit=20&cursor=...`:
  requests to pay or requests sent. Default direction is incoming; omitted
  status includes all states.
- `GET /api/money-requests/{id}`: participant-scoped request details.
- `POST /api/money-requests/{id}/pay`, `/decline`, or `/cancel`: resolve a pending
  request. No body is required; an empty object is also accepted. Only the payer
  can pay/decline; only the requester can cancel.

All money-request mutations require `Idempotency-Key`. Request states are
`PENDING`, `PAID`, `DECLINED`, and `CANCELLED`; paid requests include `transferId`.
Partial payments, edits, expiration, reminders, and notifications are deferred.
Unconfirmed request mutations retain their original input and key for safe
retry, and block conflicting new mutations until resolved.

Paged responses contain `items` and a nullable `nextCursor`. Amounts must be
positive integer cents, up to 1,000,000,000 cents per transfer. Reasons are at
most 280 characters. Errors contain `code`, `message`, and `fieldErrors`.

### Verification

Run `./gradlew test` in `backend-java/`, then `npm run typecheck` and
`npm run build` in `frontend/`. Run the focused frontend checks with
`node --test tests/*.test.mjs`. Integration checks should additionally exercise
the running UI and API, including concurrent spending, duplicate request keys,
self-transfer rejection, account-scoped history, and uncertain-outcome retries.
Request-money checks cover participant roles, terminal-state races, no funds
movement on creation/decline/cancel, and atomic payment linked to one transfer.

This is a monorepo containing both the backend and frontend components for the Brex interview playground - a simple message system to get you started coding.

## Project Structure

```
brex-interview-playground/
├── backend-java/      # Java Spring Boot Backend
│   ├── src/
│   │   └── main/
│   │       ├── java/
│   │       │   └── brex/interview/
│   │       │       ├── config/      # Application configuration
│   │       │       ├── rest/        # REST controllers
│   │       │       ├── graphql/     # GraphQL resolvers
│   │       │       ├── model/       # Domain models
│   │       │       ├── dto/         # Data transfer objects
│   │       │       ├── repository/  # Data access layer
│   │       │       └── service/     # Business logic
│   │       └── resources/
│   │           ├── graphql/         # GraphQL schema
│   │           └── schema/          # Database schema
│   └── src/test/                    # Test files
└── frontend/        # T3 Stack Frontend
    ├── src/
    │   ├── app/              # Next.js app router pages
    │   ├── components/       # React components
    │   ├── graphql/         # GraphQL queries and mutations
    │   ├── lib/             # Utility functions and configurations
    │   ├── styles/          # Global styles and Tailwind config
    │   └── types/           # TypeScript type definitions
    ├── public/              # Static assets
    └── tests/               # Test files
```

### Backend

#### Java Spring Boot Backend

The Java backend is a Spring Boot application that provides:
- REST and GraphQL APIs for simple message operations
- H2 in-memory database
- Basic message CRUD functionality

### Frontend (T3 Stack)

The frontend is built with the T3 Stack, featuring:
- Next.js 14 with App Router
- TypeScript for type safety
- Tailwind CSS for styling
- Apollo Client for GraphQL integration
- GraphQL Code Generator for type-safe GraphQL operations

### Prerequisites

For Java backend:
- JDK 17 or higher
- Gradle (for building the project)

For frontend:
- Node.js 18 or higher
- npm or yarn

## Development

### Running the Backend

#### Java Backend

1. **Build the Project**:
   ```bash
   cd backend-java
   ./gradlew build
   ```

2. **Run the Application**:
   ```bash
   ./gradlew bootRun --console=plain
   ```

3. **Access the Application**:
   - REST API: `http://localhost:8080/api/`
      - i.e. `http://localhost:8080/api/messages/latest`
   - GraphQL API: `http://localhost:8080/graphql`

### Running the Frontend

1. **Install Dependencies**:
   ```bash
   cd frontend
   npm install
   ```

2. **Start Development Server**:
   ```bash
   npm run dev
   ```

3. **Access the Application**:
   Visit `http://localhost:3000` to access the application

### GraphQL Development

#### Generating TypeScript Types
   The project uses GraphQL Code Generator to automatically generate TypeScript types from the GraphQL schema. **Make sure the backend is running when run this command.**
   ```bash
   npm run codegen
   ```

#### Testing GraphQL
- Access the GraphQL IDE:
  - For Java backend: `http://localhost:8080/graphiql`

- **Example Query**:
    ```graphql
    query {
      latestMessages(limit: 10) {
        id
        content
        createdAt
      }
    }
    ```

- **Example Mutation**:
    ```graphql
    mutation {
      createMessage {
        id
        content
        createdAt
      }
    }
    ```

### Running Tests

#### Java Backend Tests:
```bash
cd backend-java
./gradlew test
```
