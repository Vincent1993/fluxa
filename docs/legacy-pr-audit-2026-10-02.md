# Legacy pull request audit — 2026-10-02

The audit compared actual branch code against main after PR #7 (632e73ae), rather than
using PR descriptions as proof. Existing PRs and branches remain intact.

| PR / reviewed head | Useful behavior | Disposition |
| --- | --- | --- |
| #2 / 660fd09d | Initial Android scaffold and build workflow | Current main supplies the scaffold and verified three-channel CI. The old workflow references secrets directly in a step condition and publishes unsigned APKs as a fallback. Do not restore it. Suggest closing as superseded. |
| #4 / 045091ca | Incremental cache, per-source cursor, periodic refresh | Cache and cursors are already covered by the data-preserving main implementation. Port periodic refresh independently with explicit opt-in, session checks, correct WorkManager initialization and finite retries. Suggest closing the original after the replacement merges. |
| #5 / 5f6ae2fd | Offline article mutations and queue | Main already persists read/star changes and their queue atomically before networking. The old branch queues only after failure and retains destructive refresh behavior. Its default-note/default-highlight buttons and Inoreader tag conventions are prototypes, not supported NewsBlur features. Suggest closing; any annotation feature needs separate product/API work. |
| #6 / 49a7a97b | Local query and filtering | Main provides actual subscription selection, unread/starred filters and cached search. The old UI toggles hard-coded feed/tech and tech values and its database uses fallbackToDestructiveMigration. Do not merge. Suggest closing; date/tag filters can be scoped separately. |

All three experimental branches independently alter Room schema v2. Main retains an
explicit v1/v2-to-v3 migration and preserves old data and pending operations. The background
refresh port introduces no schema change and does not reactivate the Inoreader backend.

The periodic refresh replacement defaults off. When enabled it requests one job per hour
on an unmetered network with adequate battery, first eligible after one hour; Android may
delay execution. It refreshes every active subscription without changing the reader's
selected source. No authenticated session means no network request. Transient network,
429 and server errors retry at most twice per execution; cancellation propagates.

No legacy PR was directly merged, closed, or had its branch deleted during this audit.
