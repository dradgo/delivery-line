/**
 * Story 2.18 (Task 1) — frontend-owned `ClarificationsView` fixtures.
 *
 * One fixture per AC3 backend state, a multi-question grouped view, an XSS probe,
 * and the AC11 "stuck" anti-pattern fixture (answered with no follow-up event). The
 * backend exposes NO clarification-read endpoint (Dev Notes), so these are
 * frontend-owned copies of the intended read model — the region is presentational
 * and every test render is driven from here (T4 — never fabricated from live
 * `WorkflowDetail`, which carries no clarification data).
 *
 * IDs satisfy `isValidClarificationId` (`^cla_[A-Za-z0-9_-]{4,64}$`).
 */
import type { ClarificationsView, ClarificationView } from '@/features/workflows/clarificationView';

const RUN_ID = 'run_clr_demo_001';
const ARTIFACT_ID = 'art_spec_clr_001';

/** Wrap one clarification into a single-item view. */
function viewOf(clarification: ClarificationView): ClarificationsView {
  return { clarifications: [clarification] };
}

/** `open` — unanswered question awaiting a response. */
export const openClarification: ClarificationView = {
  clarificationId: 'cla_open0001',
  workflowRunId: RUN_ID,
  artifactId: ARTIFACT_ID,
  artifactVersion: 3,
  questionId: 'q-open-001',
  questionText:
    'Should the export endpoint paginate, or return the full result set in one response?',
  status: 'open',
  createdAt: '2026-05-30T12:00:00Z',
};

/** `answered` — submitted, awaiting acceptance (pending incorporation). */
export const answeredClarification: ClarificationView = {
  clarificationId: 'cla_answered01',
  workflowRunId: RUN_ID,
  artifactId: ARTIFACT_ID,
  artifactVersion: 3,
  questionId: 'q-answered-001',
  questionText: 'Which timezone should scheduled runs use by default?',
  status: 'answered',
  answerText: 'Use UTC for all scheduled runs; render local time only in the UI.',
  answeredByActor: 'pm@dradgo.org',
  answeredByActorType: 'human',
  answeredAt: '2026-05-30T12:05:00Z',
  createdAt: '2026-05-30T12:00:00Z',
};

/** `accepted` — queued for incorporation in the next spec version. */
export const acceptedClarification: ClarificationView = {
  clarificationId: 'cla_accepted01',
  workflowRunId: RUN_ID,
  artifactId: ARTIFACT_ID,
  artifactVersion: 3,
  questionId: 'q-accepted-001',
  questionText: 'Confirm the retry ceiling for failed deliveries.',
  status: 'accepted',
  answerText: 'Cap retries at 5 with exponential backoff.',
  answeredByActor: 'pm@dradgo.org',
  answeredByActorType: 'human',
  answeredAt: '2026-05-30T12:06:00Z',
  acceptedAt: '2026-05-30T12:07:00Z',
  createdAt: '2026-05-30T12:00:00Z',
};

/** `incorporated` — visibly applied into a newer spec version (the happy outcome). */
export const incorporatedClarification: ClarificationView = {
  clarificationId: 'cla_incorp0001',
  workflowRunId: RUN_ID,
  artifactId: ARTIFACT_ID,
  artifactVersion: 3,
  questionId: 'q-incorp-001',
  questionText: 'Should soft-deleted records appear in the audit export?',
  status: 'incorporated',
  answerText: 'Yes — include soft-deleted records, flagged with a `deletedAt` column.',
  answeredByActor: 'pm@dradgo.org',
  answeredByActorType: 'human',
  answeredAt: '2026-05-30T12:08:00Z',
  acceptedAt: '2026-05-30T12:09:00Z',
  incorporatedAt: '2026-05-30T12:15:00Z',
  incorporatedIntoArtifactId: 'art_spec_clr_001_v4',
  createdAt: '2026-05-30T12:00:00Z',
};

/** `superseded` — set aside without addressing, with an explicit no-effect reason (AC6). */
export const supersededClarification: ClarificationView = {
  clarificationId: 'cla_superseded1',
  workflowRunId: RUN_ID,
  artifactId: ARTIFACT_ID,
  artifactVersion: 3,
  questionId: 'q-superseded-001',
  questionText: 'Which currency formatting library should the report use?',
  status: 'superseded',
  answerText: 'Use the platform Intl.NumberFormat with the tenant locale.',
  answeredByActor: 'pm@dradgo.org',
  answeredByActorType: 'human',
  answeredAt: '2026-05-30T12:04:00Z',
  supersededByArtifactId: 'art_spec_clr_001_v4',
  noEffectReason: 'Spec rebuilt without addressing this question — superseded by spec v4.',
  createdAt: '2026-05-30T12:00:00Z',
};

/** `rejected_invalid` — rejected with a reason (AC6). */
export const rejectedInvalidClarification: ClarificationView = {
  clarificationId: 'cla_rejected01',
  workflowRunId: RUN_ID,
  artifactId: ARTIFACT_ID,
  artifactVersion: 3,
  questionId: 'q-rejected-001',
  questionText: 'How many parallel workers should the importer use?',
  status: 'rejected_invalid',
  answerText: 'purple',
  answeredByActor: 'pm@dradgo.org',
  answeredByActorType: 'human',
  answeredAt: '2026-05-30T12:03:00Z',
  noEffectReason: 'Answer text was not parseable for this question type (expected a number).',
  createdAt: '2026-05-30T12:00:00Z',
};

/** `unknown` — the hard-deleted-legacy-row sentinel (schema.d.ts:249). */
export const unknownClarification: ClarificationView = {
  clarificationId: 'cla_unknown001',
  workflowRunId: RUN_ID,
  artifactId: ARTIFACT_ID,
  artifactVersion: 3,
  questionId: 'q-unknown-001',
  questionText: 'Legacy question whose row was hard-deleted before this replay.',
  status: 'unknown',
  createdAt: '2026-05-30T11:00:00Z',
};

/**
 * AC11 anti-pattern — the backend ACKNOWLEDGED a submission (`answered`) but NO
 * follow-up `accepted`/`incorporated` event ever arrived. The UI must visibly keep
 * showing `answered / pending incorporation`, NEVER "answer received" forever.
 * Identical shape to {@link answeredClarification}; named distinctly so the
 * anti-pattern test reads intentionally.
 */
export const stuckAnsweredClarification: ClarificationView = {
  clarificationId: 'cla_stuck00001',
  workflowRunId: RUN_ID,
  artifactId: ARTIFACT_ID,
  artifactVersion: 3,
  questionId: 'q-stuck-001',
  questionText: 'Should the webhook payload include the full diff or just a summary?',
  status: 'answered',
  answerText: 'Include just a summary plus a link to the full diff.',
  answeredByActor: 'pm@dradgo.org',
  answeredByActorType: 'human',
  answeredAt: '2026-05-30T12:02:00Z',
  createdAt: '2026-05-30T12:00:00Z',
};

/**
 * XSS probe (AC8/AC11) — `questionText` AND `answerText` carry a `<script>` and an
 * `<img onerror>`. The region routes both through `SafeMarkdownRenderer`; the test
 * asserts no active `<script>`/`<iframe>` renders (it does NOT re-test the sanitizer,
 * which is story 2.24's own suite).
 */
export const xssClarification: ClarificationView = {
  clarificationId: 'cla_xss000001',
  workflowRunId: RUN_ID,
  artifactId: ARTIFACT_ID,
  artifactVersion: 3,
  questionId: 'q-xss-001',
  questionText:
    'Question with payload <script>window.__xss_executed = true;</script> and <img src="x" onerror="window.__xss_executed = true" />',
  status: 'answered',
  answerText: 'Answer with payload <script>window.__xss_executed = true;</script> trailing.',
  answeredByActor: 'pm@dradgo.org',
  answeredByActorType: 'human',
  answeredAt: '2026-05-30T12:01:00Z',
  createdAt: '2026-05-30T12:00:00Z',
};

// ── Single-state views (one clarification each) ─────────────────────────────
export const openView = viewOf(openClarification);
export const answeredView = viewOf(answeredClarification);
export const acceptedView = viewOf(acceptedClarification);
export const incorporatedView = viewOf(incorporatedClarification);
export const supersededView = viewOf(supersededClarification);
export const rejectedInvalidView = viewOf(rejectedInvalidClarification);
export const unknownView = viewOf(unknownClarification);
export const stuckAnsweredView = viewOf(stuckAnsweredClarification);
export const xssView = viewOf(xssClarification);

/** The empty view — the only live-reachable state today (disabled stub → empty). */
export const emptyView: ClarificationsView = { clarifications: [] };

/**
 * A multi-question grouped view spanning every group (open → pending → terminal →
 * unknown) so the grouping/sorting + collapse-by-default behaviour (AC2) has real
 * structure to walk. Deliberately NOT pre-sorted, to prove `groupClarificationsByStatus`
 * imposes the precedence.
 */
export const multiQuestionView: ClarificationsView = {
  clarifications: [
    incorporatedClarification,
    openClarification,
    supersededClarification,
    answeredClarification,
    rejectedInvalidClarification,
    acceptedClarification,
    unknownClarification,
  ],
};
