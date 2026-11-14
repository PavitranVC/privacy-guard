# Code Audit Rules (Max 400 Words, Zero Fluff, Do Not Restate Code)
Audit the attached files ONLY for:
1. **Android 14–16 Runtime Crashes:** Missing R8 `-keep` rules, unguarded `Shizuku.pingBinder()`, guessed hidden API signatures, main-thread blocking, hardcoded 4KB page assumptions, or ZIP/ELF/DEX `mmap` out-of-bounds reads (`SIGBUS`).
2. **State & Concurrency Bugs:** Race conditions, non-idempotent Room transactions, or infinite retries on `SecurityException`.
3. **Privacy & Attack Surface Leaks:** Unintended exported components (`android:exported="true"`), hardcoded keys, or extra fields sent in outbound HTTP/LLM payloads.

Output ONLY: `[File:Line] -> Bug -> Exact Fix`. If clean, output `PASS` + any subtle edge cases.