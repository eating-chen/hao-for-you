---
name: java-code-review
description: 'Review Java code for bugs, security vulnerabilities, performance issues, and design problems. Results organized by risk level (CRITICAL, HIGH, MEDIUM, LOW, INFO) with revision advice. Use for production-readiness checks, security audits, refactoring guidance, and code quality improvement.'
argument-hint: 'File path(s) to review, or Java class name. Example: src/main/java/com/haoforyou/service/GoogleAuthService.java'
user-invocable: true
---

# Java Code Review

## When to Use

- **Before merging**: Verify code meets quality, security, and performance standards
- **Security audit**: Find vulnerabilities, insecure patterns, and auth/crypto issues
- **Performance tuning**: Identify inefficient loops, memory leaks, connection misuse
- **Refactoring guidance**: Improve design, reduce complexity, apply design patterns
- **Production readiness**: Ensure error handling, logging, and operational health

## Review Procedure

### 1. Scope Definition
- Identify the file(s) or class(es) to review
- Understand the module's responsibility and dependencies
- Note the risk profile (e.g., auth service = CRITICAL, utility helper = LOW)

### 2. Multi-Layer Analysis

#### Layer A: Security & Safety (CRITICAL/HIGH)
- Authentication/authorization logic: roles, token validation, session management
- Sensitive data: passwords, API keys, tokens—check for hardcoding, logging, transmission
- SQL injection / injection attacks: parameterized queries, input validation
- Deserialization risks: untrusted input, gadget chains
- Cryptography: weak algorithms, predictable RNG, key management
- CORS, CSRF, XSS vulnerabilities (if applicable)

#### Layer B: Correctness & Logic (MEDIUM/HIGH)
- Null pointer exceptions: missing null checks, unchecked optionals
- Type safety: unchecked casts, generic type erasure misuse
- Resource leaks: streams, connections, files—ensure try-with-resources
- Circular dependencies: bean wiring, object graphs
- Off-by-one errors: loops, array access, pagination
- Thread safety: shared mutable state, race conditions

#### Layer C: Maintainability & Design (MEDIUM/LOW)
- Code clarity: unclear variable names, complex expressions, deep nesting
- Error handling: proper exception types, meaningful messages, recovery strategy
- Logging: appropriate levels (debug vs. info vs. warn), secret filtering
- Testability: hard dependencies, mocks, isolation
- Design patterns: SOLID principles, separation of concerns
- Code duplication: extract methods, reusable utilities

#### Layer D: Performance & Scalability (MEDIUM/LOW)
- Algorithm efficiency: O(n²) vs. O(n), caching opportunities
- N+1 queries: batch operations, eager loading
- Memory: unnecessary object allocation, collections sizing
- Concurrency: blocking operations, thread pool sizing
- Configuration: hardcoded values, environment sensitivity

### 3. Risk Classification

| Risk Level | Criteria | Impact |
|---|---|---|
| **CRITICAL** | Crashes, data loss, security breach, auth bypass | Blocks production |
| **HIGH** | Correctness bug, vulnerability, major performance degradation | Must fix before merge |
| **MEDIUM** | Design smell, maintainability concern, moderate performance cost | Should fix, can defer with ticket |
| **LOW** | Style, minor inefficiency, edge case handling | Nice to have, low priority |
| **INFO** | Best practice suggestion, code comment, learning opportunity | Informational only |

### 4. Revision Advice Template

For each finding, provide:

```
## [RISK_LEVEL] <Finding Title>

**Location**: [File]:[Line]
**Snippet**:
```java
// offending code
```

**Issue**: 
[Describe what's wrong and why it matters]

**Risk**: 
[Security/correctness/performance/maintenance impact]

**Revision**:
```java
// fixed code
```

**Explanation**:
[Why the fix works; link to patterns/docs if helpful]
```

---

## Example: Full Code Review

### Input File
```java
// File: src/main/java/com/haoforyou/service/GoogleAuthService.java (excerpt)

private String getUserEmail(String accessToken) {
    try {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://www.googleapis.com/oauth2/v3/userinfo"))
            .header("Authorization", "Bearer " + accessToken)
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        Map<?, ?> body = objectMapper.readValue(response.body(), Map.class);
        return (String) body.getOrDefault("email", "unknown@gmail.com");
    } catch (Exception e) {
        log.error("Failed to get user email", e);
        return "unknown@gmail.com";
    }
}
```

### Review Results

---

## [HIGH] HttpClient Resource Leak

**Location**: GoogleAuthService.java : Line 170-177

**Snippet**:
```java
HttpClient client = HttpClient.newHttpClient();
HttpRequest request = HttpRequest.newBuilder()...
HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
```

**Issue**: 
Creating a new `HttpClient` per request is expensive and may leak resources if the method is called frequently. `HttpClient` is intended to be reused across requests.

**Risk**: 
Performance degradation, connection pool exhaustion in production.

**Revision**:
```java
// Add class-level field:
private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

// In method:
private String getUserEmail(String accessToken) {
    try {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://www.googleapis.com/oauth2/v3/userinfo"))
            .header("Authorization", "Bearer " + accessToken)
            .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        Map<?, ?> body = objectMapper.readValue(response.body(), Map.class);
        return (String) body.getOrDefault("email", "unknown@gmail.com");
    } catch (Exception e) {
        log.error("Failed to get user email", e);
        return "unknown@gmail.com";
    }
}
```

**Explanation**:
`HttpClient` implements connection pooling and should be a shared singleton. Reusing the same client improves throughput and reduces GC pressure.

---

## [HIGH] Unsafe Cast Without Validation

**Location**: GoogleAuthService.java : Line 177

**Snippet**:
```java
return (String) body.getOrDefault("email", "unknown@gmail.com");
```

**Issue**: 
`body` is a raw `Map<?, ?>`. The cast assumes `"email"` value is a `String`, but if the JSON structure changes or an attacker controls the response, a `ClassCastException` may occur at runtime.

**Risk**: 
Potential `ClassCastException`, cryptic error messages, debugging difficulty.

**Revision**:
```java
Object emailObj = body.get("email");
String email = emailObj instanceof String ? (String) emailObj : "unknown@gmail.com";
return email;
```

**Explanation**:
Explicit type checking prevents unchecked cast errors. This follows defensive coding practice for untrusted external APIs.

---

## [MEDIUM] Catch-All Exception Masking

**Location**: GoogleAuthService.java : Line 180-182

**Snippet**:
```java
} catch (Exception e) {
    log.error("Failed to get user email", e);
    return "unknown@gmail.com";
}
```

**Issue**: 
Catching all `Exception` types conflates different failure modes:
- `HttpTimeoutException` (network issue—may retry)
- `JsonMappingException` (API response changed—log alert)
- `IOException` (connection lost—retry with backoff)

A single fallback return hides critical debugging information.

**Risk**: 
Difficult to diagnose root causes; no opportunity for graceful degradation.

**Revision**:
```java
} catch (HttpTimeoutException | HttpConnectException e) {
    log.warn("Network timeout fetching user email, will retry", e);
    throw new TemporaryException("Google API unavailable", e);
} catch (JsonMappingException e) {
    log.error("Google API response format changed, check API version", e);
    throw new PermanentException("Invalid Google API response", e);
} catch (IOException e) {
    log.error("I/O error communicating with Google", e);
    return "unknown@gmail.com";
}
```

**Explanation**:
Specific exception handling enables proper retry logic, alerting, and fallback strategies. Define custom exception types (`TemporaryException`, `PermanentException`) in your codebase for clear intent.

---

## [MEDIUM] No Timeout Configuration

**Location**: GoogleAuthService.java : Line 170-171

**Snippet**:
```java
HttpClient client = HttpClient.newHttpClient();
HttpRequest request = HttpRequest.newBuilder()...
```

**Issue**: 
No connection or request timeout is set. If Google API hangs, the method blocks indefinitely, exhausting threads.

**Risk**: 
Thread pool starvation, cascading failures, unresponsiveness.

**Revision**:
```java
private String getUserEmail(String accessToken) {
    try {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://www.googleapis.com/oauth2/v3/userinfo"))
            .header("Authorization", "Bearer " + accessToken)
            .timeout(Duration.ofSeconds(5))  // Request timeout
            .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        // ...
    } catch (HttpTimeoutException e) {
        log.warn("Google API timeout", e);
        // Handle timeout
    }
}
```

**Explanation**:
Set explicit timeouts (connect, request) to prevent indefinite blocking. Choose values based on SLA expectations (typically 5-15 seconds for external APIs).

---

## [MEDIUM] Missing Null Check for Response Body

**Location**: GoogleAuthService.java : Line 176

**Snippet**:
```java
Map<?, ?> body = objectMapper.readValue(response.body(), Map.class);
```

**Issue**: 
If `response.body()` is null or empty, `ObjectMapper.readValue()` may throw `MappingException`.

**Risk**: 
Unchecked exception, failure to handle API errors gracefully.

**Revision**:
```java
String responseBody = response.body();
if (responseBody == null || responseBody.isEmpty()) {
    throw new PermanentException("Google API returned empty response");
}
Map<?, ?> body = objectMapper.readValue(responseBody, Map.class);
```

**Explanation**:
Validate external API responses before parsing. Empty or null responses often signal API errors or network issues.

---

## [LOW] Hardcoded API URL

**Location**: GoogleAuthService.java : Line 172

**Snippet**:
```java
.uri(URI.create("https://www.googleapis.com/oauth2/v3/userinfo"))
```

**Issue**: 
API endpoint is hardcoded. If Google changes the URL or you want to test with a mock, changes require code edits.

**Risk**: 
Low operational flexibility; test environments must use production API.

**Revision**:
```java
// In application.yml:
google:
  api:
    userinfo-endpoint: "https://www.googleapis.com/oauth2/v3/userinfo"

// In class:
@Value("${google.api.userinfo-endpoint}")
private String userinfoEndpoint;

private String getUserEmail(String accessToken) {
    // ...
    .uri(URI.create(userinfoEndpoint))
    // ...
}
```

**Explanation**:
Externalize configuration to environment variables or properties files. This enables environment-specific overrides without recompilation.

---

## [INFO] Logging Does Not Redact Token

**Location**: GoogleAuthService.java : Line 173

**Snippet**:
```java
.header("Authorization", "Bearer " + accessToken)
```

**Issue**: 
If logs capture the full request, the `accessToken` will be logged and exposed in debug output.

**Risk**: 
Token leakage if logs are accessible or shared.

**Revision**:
```java
log.debug("Fetching user info from Google API (token redacted)");
// Or if logging the token in a higher-level log, ensure it's marked as sensitive:
MDC.put("accessToken", "[REDACTED]");
```

**Explanation**:
Never log sensitive credentials. Use placeholders or redaction filters in logging frameworks (e.g., Spring Cloud Vault, Logback filters).

---

## Summary

| Risk | Count | Recommendation |
|---|---|---|
| **CRITICAL** | 0 | ✅ No blocking issues |
| **HIGH** | 2 | 🔴 Fix before merge |
| **MEDIUM** | 3 | 🟡 Should fix soon |
| **LOW** | 1 | ℹ️ Nice to have |
| **INFO** | 1 | 📝 Best practice |

### Next Steps

1. **Address HIGH findings** (resource leak, unsafe cast)
2. **Improve error handling** to distinguish failure modes (timeout vs. JSON parse)
3. **Add configuration** for endpoints and timeouts
4. **Add tests** covering timeout and API error scenarios
5. **Review other auth/API methods** for similar patterns

---

## Integration with CI/CD

Use this skill in your workflow:
- **Pre-commit**: Highlight critical/high findings
- **PR review**: Share risk summary and revision advice
- **Retrospective**: Track recurring patterns

### Example CI Command

```bash
# Review changes in PR
copilot review src/main/java/com/haoforyou/service/*.java
```
