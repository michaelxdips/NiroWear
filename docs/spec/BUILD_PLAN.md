# BUILD PLAN

## Completed foundation — preserve existing implementation

1. project boots
2. static ChatScreen
3. RenderLab
4. Markdown blocks
5. math rendering

## Streaming reader regression

6. fake provider
7. incremental parsing
8. stable scroll behavior
9. interruption handling

## Priority override — secure BYOK vertical slice (early MVP gate)

10. Provider-neutral domain + ProviderProfile + fail-closed Keystore SecretStore
11. OpenAI-compatible manual/discovered model + secure watch setup
12. Real HTTP stream/cancellation + Room partial history + actual restart proof
13. Existing keyboard/voice and renderer integration
14. Native OpenAI Responses, Gemini, Anthropic from current official docs
15. Switching, linear transactional edit/retry, BYOK release-blocking matrix

## Parallel renderer and BYOK acceptance

16. Required-provider real smoke tests or explicit credential blockers
17. Secret corruption/invalidation/rotation/deletion and storage/log inspection
18. Dynamic discovery/capability metadata/manual fallback regression
19. Native math release-license resolution and overflow/Unicode regression
20. Persistent history/recovery and RenderLab streaming regression

## Phase E — Hardware hardening

21. physical Xiaomi Watch 2
22. typography tuning
23. math overflow tuning
24. performance/jank checks
25. release build hardening
