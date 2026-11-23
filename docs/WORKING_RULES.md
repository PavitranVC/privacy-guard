# Working Rules & Engineering Invariants

These rules are mandatory for all contributors and agentic assistants working on the Privacy Guard codebase.

## Core Rules

1. **Evidence Over Narrative:**
   - Every claim in reports, commits, and walkthroughs must be backed by a raw command output, test execution log, or git commit hash [owner-decision, M3.5-G prompt R1].
   - If something fails, report FAIL with the raw error output.
   - **Never write PASS unobserved.** Never assume a command worked without verifying its return code and output [owner-decision, M3.5-G prompt R1, M4-A prompt R1].

2. **Atomic Commits:**
   - One commit per numbered task item: `<type>(<scope>): item N <short description>` [owner-decision, M3.5-G prompt R5, M4-A prompt R3].
   - Commit history must remain bisectable and cleanly documented.

3. **Strict Build Invariants:**
   - **DO NOT** modify `libs.versions.toml`, `build.gradle*`, `CMakeLists.txt`, or AIDL interfaces without an explicit, approved `implementation_plan.md` [GEMINI.md:14; owner-decision, M3.5-J prompt R2, M4-A prompt R2].
   - If an item requires a build or schema change that has not been approved in an `implementation_plan.md`, **STOP** immediately and report what is required.

4. **Walkthrough Accuracy (walkthrough.md):**
   - Write **ONLY** what git history or captured raw output directly proves [GEMINI.md:20; owner-decision, M3.5-G prompt R6].
   - Do not describe code that was not changed in that milestone.
   - Do not hardcode test counts in prose; write `"see Gradle output"` to prevent stale metrics [owner-decision, M3.5-H prompt R6, M4-B prompt R4].
   - Copy git diff statistics directly from `git show --stat`, never from memory [owner-decision, M4-A prompt R5].

5. **State-Changing Device Command Ledger:**
   - For every state-changing command executed on a connected test device, write an entry to the milestone's ledger (`C:\Projects\_smoke_<milestone>\ledger.txt`) [owner-decision, M3.5-G prompt R4, M4-B prompt R6]:
     - Exact timestamp
     - Exact command executed
     - Exit code
     - Matching undo command (or note if non-reversible)
   - Every execution must save its raw stdout and stderr to a dedicated step file (e.g. `p3_<step>.txt`). A step without a raw output capture file does not count [owner-decision, M4-B prompt R6].

6. **Target Safety Boundaries:**
   - Privileged shell executions and testing on-device are restricted strictly to three test packages [owner-decision, M3.5-G prompt R3, M4-B prompt R7]:
     1. `com.ansangha.drdriving`
     2. `com.ludo.king`
     3. `com.rarlab.rar`
   - **NEVER** touch system applications, launchers, Google Mobile Services (GMS), telephony, messaging, or Shizuku itself [owner-decision, M3.5-G prompt R3].
   - **NEVER** run `adb` commands that extract personal inventories, Wi-Fi SSIDs, accounts, or private device logs [GEMINI.md:6].

7. **Prohibited Android Commands:**
   - **`gradlew connectedAndroidTest`:** Strictly forbidden [owner-decision, DECISIONS.md:D6; M3.5-J prompt R7, M4-B prompt R5]. It uninstalls the target application, permanently wiping Shizuku pairing tokens, permissions, and Room database contents. Use the manual `am instrument` flow only.
   - **Plain `pm clear <pkg>` without `--cache-only`:** Strictly forbidden [owner-decision, M3.5-G prompt R3, M4-B prompt R5]. Wipes application data.
   - **Uninstalling the main app (`org.privacyguard.android`):** Strictly forbidden [owner-decision, M3.5-G prompt R3, M4-B prompt R5].

8. **Scope Discipline:**
   - Do not fix bugs outside the explicit assignment scope [owner-decision, M3.5-G prompt R8, M3.5-J prompt R4, M4-A prompt R4].
   - Report any unexpected behaviors or defects found in existing code, but do not modify them unless explicitly instructed.

9. **Independent Audit Principle:**
   - The external auditor (Claude) verifies implementations directly against git commit ranges and repo source code, not against agent summaries or chat transcripts [owner-decision, CONTEXT.md:17].
