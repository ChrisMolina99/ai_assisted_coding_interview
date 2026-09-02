# Brex AI Assisted Interview Playground

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
