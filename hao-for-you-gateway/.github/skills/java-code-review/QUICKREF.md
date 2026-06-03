## Java Code Review Quick Reference

### How to Use This Skill

**In VS Code**, type `/java-code-review` and provide:
- File path: `src/main/java/com/haoforyou/service/GoogleAuthService.java`
- Or class name: `GoogleAuthService`

The skill will analyze and return findings organized by risk level.

---

### Risk Levels (Priority Ordering)

```
🔴 CRITICAL   → Blocks production, immediate fix
🟥 HIGH       → Must fix before merge
🟨 MEDIUM     → Should fix soon, can defer with ticket
🟩 LOW        → Nice to have, low priority
ℹ️  INFO       → Informational, best practices
```

---

### What Gets Reviewed

#### Security & Safety (CRITICAL/HIGH)
- Auth/token handling
- Sensitive data leaks
- Injection vulnerabilities
- Cryptography misuse
- Resource leaks

#### Correctness (HIGH/MEDIUM)
- Null pointer exceptions
- Type safety violations
- Circular dependencies
- Off-by-one errors
- Unhandled exceptions

#### Performance (MEDIUM/LOW)
- N+1 queries
- Inefficient algorithms
- Memory leaks
- Connection pooling
- Timeouts & concurrency

#### Design (MEDIUM/LOW)
- SOLID violations
- Code duplication
- Poor naming
- Weak logging
- Testability issues

---

### Output Format for Each Finding

```
## [RISK] Issue Title

**Location**: File:Line
**Snippet**: 
```java
// problematic code
```

**Issue**: What's wrong and why
**Risk**: Impact (security/correctness/performance/maintenance)
**Revision**:
```java
// fixed code
```
**Explanation**: Why the fix works
```

---

### Common High-Risk Patterns

| Pattern | Risk | Fix |
|---------|------|-----|
| `catch (Exception e)` | Hides failure modes | Catch specific exceptions |
| `HttpClient.newHttpClient()` per request | Resource leak | Reuse static instance |
| `(String) map.get("key")` | ClassCastException | Check type first |
| No timeout on external API | Thread starvation | Set `Duration.ofSeconds(5)` |
| Hardcoded database URL | Config inflexibility | Use `application.yml` |
| No null checks after API call | NPE | Validate response structure |
| `log.error("token=" + token)` | Secret leakage | Redact sensitive fields |

---

### Common Low-Risk Patterns

| Pattern | Risk | Fix |
|---------|------|-----|
| String concatenation in loop | Memory churn | Use `StringBuilder` |
| Unused variable | Dead code | Remove or document |
| Generic `List` without size | Minor inefficiency | Pre-size if known |
| Missing JavaDoc | Documentation gap | Add one-line summary |
| Repeated code snippet | Maintainability | Extract method |

---

### Integration with Your Workflow

#### Pre-Commit Hook
```bash
# Review changed files before staging
copilot review $(git diff --name-only --diff-filter=d | grep "\.java$")
```

#### PR Review
```bash
# Review all Java changes in PR
copilot review src/main/java/**/*.java
```

#### Code Inspection
```bash
# Audit critical service
copilot review src/main/java/com/haoforyou/service/GoogleAuthService.java
```

---

### Related Files in This Skill

- **[SKILL.md](./SKILL.md)** — Full procedure and example review
- **[PATTERNS.md](./PATTERNS.md)** — Detailed patterns & checklists
- **[TEMPLATE.md](./TEMPLATE.md)** — PR comment template

---

### Tips for Effective Reviews

1. **Read code as if you maintain it** — Would you understand it in 6 months?
2. **Focus on risk** — Critical and high issues first, then others
3. **Explain the why** — Each finding includes the rationale
4. **Offer concrete fixes** — Each issue includes a revision with explanation
5. **Distinguish policy from preference** — Tag subjective feedback as INFO
6. **Test the fixes mentally** — Does the revision actually solve the problem?

---

### Example Review Command

```bash
# Quick review of a service file
/java-code-review src/main/java/com/haoforyou/service/IntentService.java
```

**Output**:
- Summary table: CRITICAL (0), HIGH (2), MEDIUM (3), LOW (1), INFO (1)
- Each finding with location, code snippet, issue, fix, explanation
- Next steps: priority-ordered list of actions

---

### Feedback Loop

After reviewing and fixing issues:
1. Update code with revisions
2. Re-run review on fixed file
3. Verify all findings are addressed
4. Create ticket for deferred MEDIUM/LOW items
5. Merge with confidence

---

**Last Updated**: 2026-05-29
**Skill Author**: Java Code Review
**Context**: hao-for-you Java project
