# Java Code Review Patterns & Checklist

## Security Patterns

### Authentication & Authorization
- [ ] Verify token/credential validation on every protected endpoint
- [ ] Check for hardcoded secrets, API keys, or credentials
- [ ] Ensure tokens are never logged or exposed in error messages
- [ ] Verify RBAC/ABAC is enforced before sensitive operations
- [ ] Check JWT expiration handling and refresh token logic
- [ ] Validate that OAuth state parameter prevents CSRF

### Injection Prevention
- [ ] SQL: Use prepared statements, not string concatenation
- [ ] XML: Disable external entity processing
- [ ] LDAP: Escape filter characters
- [ ] OS commands: Avoid `Runtime.exec()` with untrusted input
- [ ] JSON/XML: Validate structure before deserialization

### Sensitive Data
- [ ] PII (emails, phones, SSN) should not be logged
- [ ] Passwords must be hashed (bcrypt, PBKDF2, Argon2)
- [ ] API keys and tokens: externalize via environment variables
- [ ] Encrypt at rest if data is persisted
- [ ] HTTPS required for all network communication

### Cryptography
- [ ] No weak algorithms: avoid MD5, SHA-1 for sensitive hashes
- [ ] Use `SecureRandom`, never `Random` for security-sensitive values
- [ ] Key management: rotate keys, store securely (e.g., Spring Vault)
- [ ] Verify cipher modes: GCM for symmetric encryption

---

## Correctness & Logic Patterns

### Null Safety
- [ ] Optional fields: use `Optional` instead of null checks
- [ ] Collections: handle empty lists before iteration
- [ ] External API responses: assume null/missing fields
- [ ] Defensive coding: validate constructor arguments

### Resource Management
- [ ] Database connections: use connection pools, close in finally/try-with-resources
- [ ] File I/O: use try-with-resources for `InputStream`, `OutputStream`, `Reader`, `Writer`
- [ ] HTTP clients: reuse across requests, don't create per-request
- [ ] Thread pools: define fixed sizes, use `newFixedThreadPool` or `newCachedThreadPool` carefully
- [ ] Memory: avoid circular references, weak references if needed

### Concurrency
- [ ] Shared mutable state: use `AtomicX` or `synchronized`
- [ ] Collections: use thread-safe types (`ConcurrentHashMap`, `CopyOnWriteArrayList`)
- [ ] Avoid deadlocks: consistent lock ordering
- [ ] Thread timeouts: set on blocking operations

### Error Handling
- [ ] Don't catch `Exception`, catch specific types
- [ ] Distinguish recoverable vs. fatal errors
- [ ] Provide context in exception messages
- [ ] Log stack traces only for unexpected errors

---

## Maintainability Patterns

### Code Clarity
- [ ] Variable names: avoid single letters (except loop counters `i`, `j`)
- [ ] Method names: verb-noun pattern (`getUserEmail`, `buildAuthUrl`)
- [ ] Complexity: cyclomatic complexity < 10 per method
- [ ] Comments: explain *why*, not *what* (code shows what)

### Logging Strategy
- [ ] `DEBUG`: Detailed traces for development/troubleshooting
- [ ] `INFO`: High-level application events (startup, shutdown, major operations)
- [ ] `WARN`: Recoverable errors, degraded operations
- [ ] `ERROR`: Unexpected failures, stack traces
- [ ] Never log secrets, PII, or sensitive data

### Error Messages
- [ ] Include context: what was attempted, what failed
- [ ] Avoid user-facing internal errors in logs
- [ ] Provide actionable suggestions if possible
- [ ] Example: `"Failed to fetch user info from Google API after 3 retries (timeout=5s)"`

### Design Patterns
- [ ] Dependency Injection: use constructor injection, avoid field injection
- [ ] Single Responsibility: one reason to change
- [ ] Interface segregation: don't force unused methods
- [ ] Composition over inheritance: prefer delegation
- [ ] Builder pattern for complex objects

---

## Performance Patterns

### Database
- [ ] N+1 queries: use joins, eager loading, or batch operations
- [ ] Index coverage: verify queries use indexes (EXPLAIN)
- [ ] Pagination: use LIMIT/OFFSET or keyset pagination
- [ ] Caching: cache frequently accessed, slowly-changing data

### Algorithms
- [ ] Loops: O(n) preferred, avoid O(n²) unless necessary
- [ ] Collections: right data structure for access pattern (Map for lookups, Set for uniqueness)
- [ ] Sorting: use built-in sorts (TimSort), don't reinvent
- [ ] Streaming: use Java Streams for readability if performance acceptable

### Memory
- [ ] Object allocation: avoid in tight loops
- [ ] String concatenation: use `StringBuilder` in loops
- [ ] Collections sizing: pre-size large collections
- [ ] Garbage collection: avoid full GC pauses with appropriate heap sizing

### Concurrency
- [ ] Blocking operations: offload to thread pools (don't block main handler threads)
- [ ] HTTP timeouts: always set (5-15 seconds typical for external APIs)
- [ ] Connection pooling: configure pool size based on workload
- [ ] Rate limiting: implement circuit breakers for external APIs

---

## Spring Boot Specific

### Bean Wiring
- [ ] Constructor injection: preferred, testable
- [ ] Avoid circular dependencies: use `@Lazy` or `ObjectProvider<T>` if needed
- [ ] `@Transactional`: use appropriate propagation levels
- [ ] Bean scope: default `singleton` suitable for stateless services

### REST Controllers
- [ ] Input validation: use `@Valid`, `@NotNull`, etc.
- [ ] Error handling: `@ExceptionHandler` for consistent error responses
- [ ] Content negotiation: specify `produces = MediaType.APPLICATION_JSON_VALUE`
- [ ] HTTP status codes: correct semantics (200, 201, 400, 401, 404, 500)

### Data Access
- [ ] JPA: use `Optional<T>` return types
- [ ] Transaction boundaries: service layer, not repository layer
- [ ] Lazy loading: be aware of N+1 query patterns
- [ ] Connection timeouts: configure in `application.yml`

---

## Checklist by Risk

### Before Code Review
- [ ] Code compiles without warnings (enable `-Xlint:all`)
- [ ] Unit tests written and passing
- [ ] No TODO/FIXME comments without tickets
- [ ] No debug logging left in
- [ ] No hardcoded credentials

### During Review: Critical Issues
- [ ] Auth/crypto/injection vulnerabilities
- [ ] Data loss or corruption risks
- [ ] Crashes or exceptions in happy path
- [ ] Resource leaks (connections, memory)
- [ ] Circular dependencies

### During Review: High Issues
- [ ] Logic errors, off-by-one bugs
- [ ] Null pointer exceptions
- [ ] Incorrect error handling
- [ ] Type safety violations
- [ ] Missing validation on inputs

### During Review: Medium Issues
- [ ] Inefficient algorithms
- [ ] Code duplication
- [ ] Poor naming or comments
- [ ] Design anti-patterns
- [ ] Missing test coverage

### During Review: Low Issues
- [ ] Style inconsistencies
- [ ] Minor inefficiencies
- [ ] Spacing/formatting
- [ ] Documentation gaps

---

## References

- [OWASP Top 10](https://owasp.org/www-project-top-ten/)
- [Spring Security Best Practices](https://spring.io/guides/topicals/spring-security-architecture/)
- [Java Concurrency in Practice](https://www.oreilly.com/library/view/java-concurrency-in/0596007124/)
- [Effective Java (3rd Edition)](https://www.oreilly.com/library/view/effective-java-3rd/9780134685991/)
