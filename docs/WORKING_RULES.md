# Working Rules & Engineering Invariants

These rules are mandatory for all contributors and agentic assistants working on the Privacy Guard codebase.

## Core Rules

1. **Evidence Over Narrative:**
   - Every claim in reports, commits, and walkthroughs must be backed by a raw command output, test execution log, or git commit hash [standing-rule].
   - If something fails, report FAIL with the raw error output.
   - **Never write PASS unobserved.** Never assume a command worked without verifying its return code and output.

2. **Atomic Commits:**
   - One commit per numbered task item: `<type>(<scope>): item N <short description>` [standing-rule].
   - Commit history must remain bisectable and cleanly documented.

3. **Strict Build Invariants (GEMINI.md):**
   - **DO NOT** modify `libs.versions.toml`, `build.gradle*`, `CMakeLists.txt`, or AIDL interfaces without an explicit, approved `implementation_plan.md` [standing-rule].
   - If an item requires a build or schema change that has not been approved in an `implementation_plan.md`, **STOP** immediately and report what is required.

4. **Walkthrough Accuracy (walkthrough.md):**
   - Write **ONLY** what git history or captured raw output directly proves [standing-rule].
   - Do not describe code that was not changed in that milestone.
   - Do not hardcode test counts in prose; write `"see Gradle output"` to prevent stale metrics.
   - Copy git diff statistics directly from `git show --stat`, never from memory.

5. **State-Changing Device Command Ledger:**
   - For every state-changing command executed on a connected test device, write an entry to the milestone's ledger (`C:\Projects\_smoke_<milestone>\ledger.txt`) [standing-rule]:
     - Exact timestamp
     - Exact command executed
     - Exit code
     - Matching undo command (or note if non-reversible)
   - Every execution must save its raw stdout and stderr to a dedicated step file (e.g. `p3_<step>.txt`). A step without a raw output capture file does not count.

6. **Target Safety Boundaries:**
   - On-device testing and privileged shell executions are restricted strictly to three test packages [standing-rule]:
     1. `com.ansangha.drdriving`
     2. `com.ludo.king`
     3. `com.rarlab.rar`
   - **NEVER** touch system applications, launchers, Google Mobile Services (GMS), telephony, messaging, or Shizuku itself.
   - **NEVER** run `adb` commands that extract personal inventories, Wi-Fi SSIDs, accounts, or private device logs.

7. **Prohibited Android Commands:**
   - **`gradlew connectedAndroidTest`:** Strictly forbidden [standing-rule]. It uninstalls the target application, which permanently wipes Shizuku pairing tokens, permissions, and Room database contents. Use the manual `am instrument` flow only.
   - **Plain `pm clear <pkg>` without `--cache-only`:** Strictly forbidden [standing-rule]. Wipes application data.
   - **Uninstalling the main app (`org.privacyguard.android`):** Strictly forbidden [standing-rule].

8. **Scope Discipline:**
   - Do not fix bugs outside the explicit assignment scope [standing-rule].
   - Report any unexpected behaviors or defects found in existing code, but do not modify them unless explicitly instructed.

9. **Independent Audit Principle:**
   - The external auditor (Claude) verifies implementations directly against git commit ranges and repo source code, not against agent summaries or chat transcripts [standing-rule].
