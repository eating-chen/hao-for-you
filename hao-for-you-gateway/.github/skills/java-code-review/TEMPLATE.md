### Review Report Template

Use this template when performing a code review to organize findings by risk:

```markdown
# Code Review: [File or Class Name]

**Reviewed By**: [Your Name]
**Date**: [Date]
**Severity Summary**: 
- CRITICAL: [#]
- HIGH: [#]
- MEDIUM: [#]
- LOW: [#]
- INFO: [#]

## CRITICAL Issues

_Blocks production, immediate fix required._

### [Issue 1]
- **Location**: Line XXX
- **Problem**: 
- **Fix**: 
- **Priority**: CRITICAL

---

## HIGH Issues

_Must fix before merge._

### [Issue 2]
- **Location**: Line XXX
- **Problem**: 
- **Fix**: 
- **Priority**: HIGH

---

## MEDIUM Issues

_Should fix soon, can create ticket._

### [Issue 3]
- **Location**: Line XXX
- **Problem**: 
- **Fix**: 
- **Priority**: MEDIUM

---

## LOW Issues

_Nice to have._

### [Issue 4]
- **Location**: Line XXX
- **Problem**: 
- **Fix**: 
- **Priority**: LOW

---

## INFO & Suggestions

_Informational, best practices._

### [Suggestion 1]
- **Topic**: 
- **Observation**: 
- **Benefit**: 

---

## Summary

✅ **Verdict**: [APPROVED / APPROVED WITH CHANGES / NEEDS REVISION]

**Next Steps**:
1. 
2. 
3. 

**Follow-up Review**: [Date or PR #]
```

### Quick Risk Classification

| Risk | Definition | Example |
|------|-----------|---------|
| CRITICAL | Data loss, security breach, crash in main flow | SQL injection, hardcoded password |
| HIGH | Significant correctness or security bug | Missing null check causing NPE, resource leak |
| MEDIUM | Design smell, maintainability, moderate perf issue | N+1 query, catch-all exception |
| LOW | Style, minor inefficiency | Unused variable, suboptimal loop |
| INFO | Best practice, learning opportunity | Extract method, add logging |

### Usage in VS Code

1. When reviewing a Java file, use `/java-code-review` slash command
2. Paste file path or class name
3. Copy results into PR comment template above
4. Track issues in your project management tool

### Example Risk Badge Markdown

```markdown
🔴 **[CRITICAL]** Security: SQL injection
🟥 **[HIGH]** Bug: Resource leak
🟨 **[MEDIUM]** Design: N+1 query
🟩 **[LOW]** Style: Variable naming
ℹ️ **[INFO]** Suggestion: Extract method
```
