//! Per-channel booked user balance, kept apart from the channel rows and created only on first anchor.

use rusqlite::{params, Connection, OptionalExtension, Result as SqliteResult, Row, TransactionBehavior};

use super::Database;
use crate::ledger::{self, LedgerCompleteness, LedgerEventDraft, LedgerRef};

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct ForwardCursor {
    pub spent_msat: u64,
    pub received_msat: u64,
    pub spent_count: u64,
    pub received_count: u64,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum CheckState {
    #[default]
    Ok,
    Watching,
    Paused,
}

impl CheckState {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Ok => "ok",
            Self::Watching => "watching",
            Self::Paused => "paused",
        }
    }

    fn parse(text: &str) -> Self {
        match text {
            "watching" => Self::Watching,
            "paused" => Self::Paused,
            _ => Self::Ok,
        }
    }
}

/// Below means the user holds less than the books say.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GapDirection {
    Below,
    Above,
}

impl GapDirection {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Below => "below",
            Self::Above => "above",
        }
    }

    fn parse(text: &str) -> Option<Self> {
        match text {
            "below" => Some(Self::Below),
            "above" => Some(Self::Above),
            _ => None,
        }
    }
}

/// Which figure a gap below is: the user's balance under the books, or the backing above them.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GapKind {
    Balance,
    Backing,
}

impl GapKind {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Balance => "balance",
            Self::Backing => "backing",
        }
    }

    fn parse(text: &str) -> Option<Self> {
        match text {
            "balance" => Some(Self::Balance),
            "backing" => Some(Self::Backing),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct BooksCheck {
    pub state: CheckState,
    pub direction: Option<GapDirection>,
    pub gap_msat: u64,
    pub first_seen_ms: Option<i64>,
    pub sightings: u8,
    pub accepted_gap_msat: u64,
    /// Backing above the books and the live balance that the operator kept, so only growth beyond it counts.
    pub accepted_backing_gap_msat: u64,
    pub checked_at_ms: Option<i64>,
    /// Which figure a gap below is; None without one, or on a check saved before it was recorded.
    pub gap_kind: Option<GapKind>,
}

/// Booked spends the LSP has not cut yet: what they spent, and how much of it each spend's booking found beyond spare BTC, which their cut takes out of the stable part.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct UncutSpends {
    pub spent_msat: u64,
    pub overflow_msat: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BookedBalance {
    pub user_channel_id: u128,
    pub channel_id: String,
    pub booked_user_msat: u64,
    pub channel_value_sats: u64,
    pub cursor: ForwardCursor,
    pub check: BooksCheck,
    pub closed_at: Option<i64>,
    pub uncut: Option<UncutSpends>,
    /// Backing above the live balance when the operator kept the books, which only a resolution writes; 0 without one.
    pub accepted_shortfall_msat: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BookReason {
    ForwardOut,
    ForwardIn,
    ForwardCatchUp,
    TopUpClaimed,
    LspPayment,
    SignedPayBack,
    InferredPayBack,
    TradeFee,
    Splice,
    UnattributedReceipt,
}

impl BookReason {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::ForwardOut => "forward_out",
            Self::ForwardIn => "forward_in",
            Self::ForwardCatchUp => "forward_catch_up",
            Self::TopUpClaimed => "top_up_claimed",
            Self::LspPayment => "lsp_payment",
            Self::SignedPayBack => "signed_pay_back",
            Self::InferredPayBack => "inferred_pay_back",
            Self::TradeFee => "trade_fee",
            Self::Splice => "splice",
            Self::UnattributedReceipt => "unattributed_receipt",
        }
    }
}

#[derive(Debug, Clone)]
pub struct BookChange {
    pub user_channel_id: u128,
    pub delta_msat: i64,
    pub reason: BookReason,
    pub evidence: String,
    pub cursor: Option<ForwardCursor>,
    pub channel_value_sats: Option<u64>,
    pub detail: serde_json::Value,
    /// Whether a spend this books is left marked uncut, in the same write, until the LSP cuts it.
    pub marks_uncut: bool,
    /// The user's live balance a listing read after the spend showed; while the books show a gap below, the spend's overflow is taken from no more than it plus the spend.
    pub listed_after_msat: Option<u64>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BookOutcome {
    Applied { before_msat: u64, after_msat: u64 },
    AlreadyBooked,
    NotAnchored,
}

/// How the operator resolves a paused check: the gap was the user's spend, a payment to the LSP, or the books stand.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GapChoice {
    Spend,
    PaymentToLsp,
    Keep,
}

impl GapChoice {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Spend => "spend",
            Self::PaymentToLsp => "payment_to_lsp",
            Self::Keep => "keep",
        }
    }
}

/// What a resolution writes: the books, the gaps it accepts, and the channel's allocation when it changes.
#[derive(Debug, Clone)]
pub struct GapResolution {
    pub choice: GapChoice,
    pub new_booked_msat: u64,
    pub accepted_gap_msat: u64,
    pub accepted_backing_gap_msat: u64,
    /// Backing above the live balance the operator saw when keeping the books; 0 for any other choice.
    pub accepted_shortfall_msat: u64,
    /// The channel's target, backing and native to save with the books; None leaves its row as it is.
    pub channel: Option<(f64, u64, u64)>,
    /// The channel's note, saved unchanged with its row.
    pub note: Option<String>,
    pub operator: String,
    pub reason: Option<String>,
    pub detail: serde_json::Value,
}

/// A ledger row appended in a transaction, to be mirrored to the audit log after the commit.
type Committed = Option<(LedgerEventDraft, i64)>;

const COLUMNS: &str = "user_channel_id, channel_id, booked_user_msat, channel_value_sats,
    fwd_spent_msat, fwd_received_msat, fwd_spent_count, fwd_received_count,
    check_state, gap_direction, gap_msat, gap_first_seen_ms, gap_sightings, accepted_gap_msat,
    checked_at_ms, closed_at, uncut_spend_msat, uncut_overflow_msat, accepted_backing_gap_msat, gap_kind, accepted_shortfall_msat";

/// Adds the columns later releases added to books made before them; a column already there is not an error, and a database without books gets no table.
pub(super) fn add_later_columns(conn: &Connection) -> SqliteResult<()> {
    if !table_exists(conn)? {
        return Ok(());
    }
    for column in [
        "uncut_spend_msat INTEGER NOT NULL DEFAULT 0",
        "accepted_backing_gap_msat INTEGER NOT NULL DEFAULT 0",
        "gap_kind TEXT",
        "accepted_shortfall_msat INTEGER NOT NULL DEFAULT 0",
        "uncut_overflow_msat INTEGER NOT NULL DEFAULT 0",
    ] {
        match conn.execute(&format!("ALTER TABLE booked_balances ADD COLUMN {column}"), []) {
            Ok(_) => {}
            Err(rusqlite::Error::SqliteFailure(_, Some(message))) if message.starts_with("duplicate column name") => {}
            Err(e) => return Err(e),
        }
    }
    Ok(())
}

/// The `ledger_metadata` key that marks the log-only pauses of the first books release as reset.
const PAUSE_RESET_KEY: &str = "booked_balance_pr2_pause_reset";

/// Once per database, turns the first release's log-only pauses back into a gap being watched, so none pauses payments without a fresh reading; each channel reset gets a ledger row.
pub(super) fn reset_log_only_pauses(conn: &Connection) -> SqliteResult<()> {
    let tx = conn.unchecked_transaction()?;
    let done: bool = tx.query_row("SELECT EXISTS(SELECT 1 FROM ledger_metadata WHERE key = ?1)", [PAUSE_RESET_KEY], |row| row.get(0))?;
    if done {
        return Ok(());
    }
    let paused: Vec<(String, String)> = if table_exists(&tx)? {
        let mut stmt = tx.prepare("SELECT user_channel_id, channel_id FROM booked_balances WHERE check_state = 'paused' ORDER BY user_channel_id")?;
        let rows = stmt.query_map([], |row| Ok((row.get(0)?, row.get(1)?)))?;
        rows.collect::<SqliteResult<_>>()?
    } else {
        Vec::new()
    };
    let mut committed = Vec::new();
    if !paused.is_empty() {
        tx.execute("UPDATE booked_balances SET check_state = 'watching', gap_sightings = 1 WHERE check_state = 'paused'", [])?;
        let now_ms = chrono::Utc::now().timestamp_millis();
        for (uid, channel_id) in &paused {
            let detail = serde_json::json!({ "user_channel_id": uid, "from": "paused", "to": "watching", "reason": "log_only_pause" });
            let row = draft("BOOKED_BALANCE_CHECK_RESET", "info", now_ms, Some(format!("booked-check-reset:{uid}")), uid, channel_id, detail);
            let appended = ledger::append_on_connection(&tx, &row)?;
            if appended.inserted {
                committed.push((row, appended.event_id));
            }
        }
    }
    tx.execute("INSERT INTO ledger_metadata (key, value) VALUES (?1, ?2)", params![PAUSE_RESET_KEY, paused.len().to_string()])?;
    tx.commit()?;
    for entry in committed {
        mirror(Some(entry));
    }
    Ok(())
}

fn create_table(conn: &Connection) -> SqliteResult<()> {
    conn.execute_batch(
        "CREATE TABLE IF NOT EXISTS booked_balances (
            user_channel_id TEXT PRIMARY KEY,
            channel_id TEXT NOT NULL,
            booked_user_msat INTEGER NOT NULL,
            channel_value_sats INTEGER NOT NULL,
            fwd_spent_msat INTEGER NOT NULL,
            fwd_received_msat INTEGER NOT NULL,
            fwd_spent_count INTEGER NOT NULL,
            fwd_received_count INTEGER NOT NULL,
            check_state TEXT NOT NULL DEFAULT 'ok',
            gap_direction TEXT,
            gap_msat INTEGER NOT NULL DEFAULT 0,
            gap_first_seen_ms INTEGER,
            gap_sightings INTEGER NOT NULL DEFAULT 0,
            accepted_gap_msat INTEGER NOT NULL DEFAULT 0,
            checked_at_ms INTEGER,
            anchored_at_ms INTEGER NOT NULL,
            updated_at_ms INTEGER NOT NULL,
            closed_at INTEGER,
            uncut_spend_msat INTEGER NOT NULL DEFAULT 0,
            accepted_backing_gap_msat INTEGER NOT NULL DEFAULT 0,
            gap_kind TEXT,
            accepted_shortfall_msat INTEGER NOT NULL DEFAULT 0,
            uncut_overflow_msat INTEGER NOT NULL DEFAULT 0
        );",
    )?;
    add_later_columns(conn)
}

fn table_exists(conn: &Connection) -> SqliteResult<bool> {
    conn.query_row(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'booked_balances'",
        [],
        |_| Ok(()),
    )
    .optional()
    .map(|row| row.is_some())
}

fn to_i64(value: u64) -> i64 {
    i64::try_from(value).unwrap_or(i64::MAX)
}

fn to_u64(value: i64) -> u64 {
    u64::try_from(value).unwrap_or(0)
}

fn corrupt(index: usize, message: &str) -> rusqlite::Error {
    rusqlite::Error::FromSqlConversionFailure(index, rusqlite::types::Type::Text, message.to_owned().into())
}

fn read_row(row: &Row) -> SqliteResult<BookedBalance> {
    let uid: String = row.get(0)?;
    let direction: Option<String> = row.get(9)?;
    Ok(BookedBalance {
        user_channel_id: uid.parse().map_err(|_| corrupt(0, "user_channel_id is not a number"))?,
        channel_id: row.get(1)?,
        booked_user_msat: to_u64(row.get(2)?),
        channel_value_sats: to_u64(row.get(3)?),
        cursor: ForwardCursor {
            spent_msat: to_u64(row.get(4)?),
            received_msat: to_u64(row.get(5)?),
            spent_count: to_u64(row.get(6)?),
            received_count: to_u64(row.get(7)?),
        },
        check: BooksCheck {
            state: CheckState::parse(&row.get::<_, String>(8)?),
            direction: direction.as_deref().and_then(GapDirection::parse),
            gap_msat: to_u64(row.get(10)?),
            first_seen_ms: row.get(11)?,
            sightings: u8::try_from(row.get::<_, i64>(12)?).unwrap_or(u8::MAX),
            accepted_gap_msat: to_u64(row.get(13)?),
            accepted_backing_gap_msat: to_u64(row.get(18)?),
            checked_at_ms: row.get(14)?,
            gap_kind: row.get::<_, Option<String>>(19)?.as_deref().and_then(GapKind::parse),
        },
        closed_at: row.get(15)?,
        uncut: uncut_spends(to_u64(row.get(16)?), to_u64(row.get(17)?)),
        accepted_shortfall_msat: to_u64(row.get(20)?),
    })
}

fn uncut_spends(spent_msat: u64, overflow_msat: u64) -> Option<UncutSpends> {
    (spent_msat > 0 || overflow_msat > 0).then_some(UncutSpends { spent_msat, overflow_msat })
}

fn draft(
    event_type: &str,
    severity: &str,
    now_ms: i64,
    dedup_key: Option<String>,
    uid: &str,
    channel_id: &str,
    detail: serde_json::Value,
) -> LedgerEventDraft {
    LedgerEventDraft {
        event_type: event_type.to_owned(),
        category: "channel".to_owned(),
        severity: severity.to_owned(),
        status: "completed".to_owned(),
        source: "database".to_owned(),
        completeness: LedgerCompleteness::Observed,
        occurred_at_ms: now_ms,
        dedup_key,
        before: None,
        after: None,
        detail,
        refs: vec![LedgerRef::new("user_channel_id", uid), LedgerRef::new("channel_id", channel_id)],
    }
}

fn cursor_json(cursor: &ForwardCursor) -> serde_json::Value {
    serde_json::json!({
        "spent_msat": cursor.spent_msat,
        "received_msat": cursor.received_msat,
        "spent_count": cursor.spent_count,
        "received_count": cursor.received_count,
    })
}

fn merge_detail(base: &mut serde_json::Value, extra: &serde_json::Value) {
    if let (Some(base), Some(extra)) = (base.as_object_mut(), extra.as_object()) {
        for (key, value) in extra {
            base.insert(key.clone(), value.clone());
        }
    }
}

fn open_row(conn: &Connection, uid: &str) -> SqliteResult<Option<(String, u64)>> {
    if !table_exists(conn)? {
        return Ok(None);
    }
    let row: Option<(String, i64)> = conn
        .query_row(
            "SELECT channel_id, booked_user_msat FROM booked_balances
             WHERE user_channel_id = ?1 AND closed_at IS NULL",
            params![uid],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .optional()?;
    Ok(row.map(|(channel_id, booked)| (channel_id, to_u64(booked))))
}

/// The start of every ledger key a change of `reason` to these books is written under.
fn key_prefix(uid: &str, reason: BookReason) -> String {
    format!("booked:{uid}:{}:", reason.as_str())
}

/// Whether a note made at `made_at` (unix seconds) is no older than the channel's open books and every splice booked on them.
pub(crate) fn noted_since_books(conn: &Connection, uid: &str, made_at: i64) -> SqliteResult<bool> {
    if !table_exists(conn)? {
        return Ok(false);
    }
    let anchored_at_ms: Option<i64> = conn
        .query_row(
            "SELECT anchored_at_ms FROM booked_balances WHERE user_channel_id = ?1 AND closed_at IS NULL",
            params![uid],
            |row| row.get(0),
        )
        .optional()?;
    let Some(anchored_at_ms) = anchored_at_ms else {
        return Ok(false);
    };
    // ';' sorts right after ':', so the range holds exactly the splice keys and is read off the key's index.
    let from = key_prefix(uid, BookReason::Splice);
    let to = format!("{};", from.trim_end_matches(':'));
    let last_splice_ms: Option<i64> = conn.query_row(
        "SELECT MAX(occurred_at_ms) FROM ledger_events WHERE dedup_key >= ?1 AND dedup_key < ?2",
        params![from, to],
        |row| row.get(0),
    )?;
    Ok(made_at >= anchored_at_ms / 1000 && last_splice_ms.is_none_or(|ms| made_at >= ms / 1000))
}

/// Applies one change to the books and appends its ledger row, returned for mirroring after the commit; a repeated evidence key is a no-op.
pub(crate) fn book_and_capture(
    conn: &Connection,
    change: &BookChange,
    now_ms: i64,
) -> SqliteResult<(BookOutcome, Committed)> {
    let uid = change.user_channel_id.to_string();
    let Some((channel_id, before)) = open_row(conn, &uid)? else {
        return Ok((BookOutcome::NotAnchored, None));
    };
    let wanted = i128::from(before) + i128::from(change.delta_msat);
    let after = u64::try_from(wanted.max(0)).unwrap_or(u64::MAX);
    let clamped = u64::try_from((-wanted).max(0)).unwrap_or(u64::MAX);
    let mut detail = serde_json::json!({
        "reason": change.reason.as_str(),
        "evidence": change.evidence,
        "delta_msat": change.delta_msat,
        "booked_before_msat": before,
        "booked_after_msat": after,
        "clamped_msat": clamped,
        "cursor": change.cursor.as_ref().map(cursor_json),
        "channel_value_sats": change.channel_value_sats,
    });
    merge_detail(&mut detail, &change.detail);
    // A spend the LSP may need to cut stays marked until the save that cuts it, so a crash or a missing price cannot lose it.
    let marked_msat = if change.marks_uncut { before.saturating_sub(after) } else { 0 };
    let mark = if marked_msat > 0 { Some(weigh_mark(conn, &uid, before, marked_msat, change.listed_after_msat)?) } else { None };
    if let (Some(mark), Some(detail)) = (&mark, detail.as_object_mut()) {
        detail.insert("uncut_spend_msat".to_owned(), serde_json::json!(mark.uncut.spent_msat));
        detail.insert("uncut_overflow_msat".to_owned(), serde_json::json!(mark.uncut.overflow_msat));
        detail.insert("overflow_msat".to_owned(), serde_json::json!(mark.overflow_msat));
        detail.insert("overflow_balance_before_msat".to_owned(), serde_json::json!(mark.balance_before_msat));
        detail.insert("overflow_backing_sats".to_owned(), serde_json::json!(mark.backing_sats));
    }
    let key = format!("{}{}", key_prefix(&uid, change.reason), change.evidence);
    let row = draft("BOOKED_BALANCE_CHANGED", "info", now_ms, Some(key), &uid, &channel_id, detail);
    let appended = ledger::append_on_connection(conn, &row)?;
    if !appended.inserted {
        return Ok((BookOutcome::AlreadyBooked, None));
    }
    if let Some(mark) = &mark {
        add_mark(conn, &uid, marked_msat, mark.overflow_msat)?;
    }
    conn.execute(
        "UPDATE booked_balances SET booked_user_msat = ?2, updated_at_ms = ?3 WHERE user_channel_id = ?1",
        params![uid, to_i64(after), now_ms],
    )?;
    if let Some(cursor) = &change.cursor {
        conn.execute(
            "UPDATE booked_balances SET fwd_spent_msat = ?2, fwd_received_msat = ?3,
                 fwd_spent_count = ?4, fwd_received_count = ?5 WHERE user_channel_id = ?1",
            params![
                uid,
                to_i64(cursor.spent_msat),
                to_i64(cursor.received_msat),
                to_i64(cursor.spent_count),
                to_i64(cursor.received_count)
            ],
        )?;
    }
    if let Some(value) = change.channel_value_sats {
        conn.execute(
            "UPDATE booked_balances SET channel_value_sats = ?2 WHERE user_channel_id = ?1",
            params![uid, to_i64(value)],
        )?;
    }
    Ok((BookOutcome::Applied { before_msat: before, after_msat: after }, Some((row, appended.event_id))))
}

/// Books a rejected or refused trade's fee, marked as `change` says; a booking of it that carries no mark, as the release before wrote them on arrival, is marked now, as spent now.
pub(crate) fn book_refused_fee_and_capture(conn: &Connection, change: &BookChange, now_ms: i64) -> SqliteResult<(BookOutcome, Committed)> {
    let (outcome, committed) = book_and_capture(conn, change, now_ms)?;
    if outcome == BookOutcome::AlreadyBooked && change.marks_uncut {
        mark_unmarked_booking(conn, change)?;
    }
    Ok((outcome, committed))
}

/// Marks the spend an earlier booking of `change`'s evidence took off the books, unless that booking was marked or took nothing; the caller's decision row keeps it from being marked twice.
fn mark_unmarked_booking(conn: &Connection, change: &BookChange) -> SqliteResult<()> {
    let uid = change.user_channel_id.to_string();
    let key = format!("{}{}", key_prefix(&uid, change.reason), change.evidence);
    let detail: Option<String> = conn.query_row("SELECT detail_json FROM ledger_events WHERE dedup_key = ?1", params![key], |row| row.get(0)).optional()?;
    let Some(detail) = detail.and_then(|text| serde_json::from_str::<serde_json::Value>(&text).ok()) else {
        return Ok(());
    };
    if detail.get("uncut_spend_msat").is_some() {
        return Ok(());
    }
    let figure = |name: &str| detail.get(name).and_then(serde_json::Value::as_u64);
    let (Some(before_msat), Some(after_msat)) = (figure("booked_before_msat"), figure("booked_after_msat")) else {
        return Ok(());
    };
    let spent_msat = before_msat.saturating_sub(after_msat);
    if spent_msat == 0 {
        return Ok(());
    }
    let Some((_, booked_msat)) = open_row(conn, &uid)? else {
        return Ok(());
    };
    // Weighed as a spend booked now would be: from the books as they stand plus this spend.
    let mark = weigh_mark(conn, &uid, booked_msat.saturating_add(spent_msat), spent_msat, change.listed_after_msat)?;
    add_mark(conn, &uid, spent_msat, mark.overflow_msat)
}

/// How a spend being marked uncut was weighed, and the channel's marks with it.
struct Mark {
    overflow_msat: u64,
    balance_before_msat: u64,
    backing_sats: u64,
    uncut: UncutSpends,
}

/// Weighs a spend of `spent_msat` from books of `before_msat` as it is marked: what spare BTC cannot cover comes out of the stable part, against the channel row's backing less what the spends still marked will take; the books are weighed without the gap the operator kept, and while they show a gap below, the balance before it is no more than the listing after it plus the spend.
fn weigh_mark(conn: &Connection, uid: &str, before_msat: u64, spent_msat: u64, listed_after_msat: Option<u64>) -> SqliteResult<Mark> {
    let (state, direction, uncut_spent, uncut_overflow, accepted_gap): (String, Option<String>, i64, i64, i64) = conn.query_row(
        "SELECT check_state, gap_direction, uncut_spend_msat, uncut_overflow_msat, accepted_gap_msat FROM booked_balances WHERE user_channel_id = ?1",
        params![uid],
        |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?, row.get(4)?)),
    )?;
    let gap_below = match CheckState::parse(&state) {
        CheckState::Paused => true,
        CheckState::Watching => direction.as_deref().and_then(GapDirection::parse) == Some(GapDirection::Below),
        _ => false,
    };
    // The listing can only lower the books less the gap kept, never raise it.
    let weighed_msat = before_msat.saturating_sub(to_u64(accepted_gap));
    let balance_before_msat = match listed_after_msat {
        Some(listed_msat) if gap_below => weighed_msat.min(listed_msat.saturating_add(spent_msat)),
        _ => weighed_msat,
    };
    let (uncut_spent, uncut_overflow) = (to_u64(uncut_spent), to_u64(uncut_overflow));
    let row_backing_sats = super::channel_books(conn, uid)?.map_or(0, |row| to_u64(row.3));
    let backing_sats = row_backing_sats.saturating_sub(uncut_overflow.div_ceil(1000));
    // Rounded as the cut from a live balance is: the balance floored and the spend rounded up to whole sats.
    let overflow_sats = crate::stable::stable_overflow_sats(balance_before_msat / 1000, spent_msat.div_ceil(1000), backing_sats);
    let overflow_msat = overflow_sats.saturating_mul(1000);
    let uncut = UncutSpends { spent_msat: uncut_spent.saturating_add(spent_msat), overflow_msat: uncut_overflow.saturating_add(overflow_msat) };
    Ok(Mark { overflow_msat, balance_before_msat, backing_sats, uncut })
}

/// Adds a spend and its overflow to the channel's marks.
fn add_mark(conn: &Connection, uid: &str, spent_msat: u64, overflow_msat: u64) -> SqliteResult<()> {
    conn.execute(
        "UPDATE booked_balances SET uncut_spend_msat = uncut_spend_msat + ?2, uncut_overflow_msat = uncut_overflow_msat + ?3
         WHERE user_channel_id = ?1",
        params![uid, to_i64(spent_msat), to_i64(overflow_msat)],
    )?;
    Ok(())
}

/// Closes the books of a channel at `now` (milliseconds; `closed_at` is stored in unix seconds), returning the ledger row for mirroring after the commit; a missing table or row is not an error.
pub(crate) fn close_and_capture(conn: &Connection, user_channel_id: &str, now: i64) -> SqliteResult<Committed> {
    let Some((channel_id, booked)) = open_row(conn, user_channel_id)? else {
        return Ok(None);
    };
    conn.execute(
        "UPDATE booked_balances SET closed_at = ?2, updated_at_ms = ?3 WHERE user_channel_id = ?1",
        params![user_channel_id, now / 1000, now],
    )?;
    let row = draft(
        "BOOKED_BALANCE_CLOSED",
        "info",
        now,
        None,
        user_channel_id,
        &channel_id,
        serde_json::json!({ "user_channel_id": user_channel_id, "booked_user_msat": booked }),
    );
    let appended = ledger::append_on_connection(conn, &row)?;
    Ok(appended.inserted.then_some((row, appended.event_id)))
}

/// Resets a paused channel's books to `resolution` and appends its ledger row, returned for mirroring after the commit; None when the books are not paused.
fn resolve_and_capture(conn: &Connection, uid: &str, resolution: &GapResolution, now_ms: i64) -> SqliteResult<Option<Committed>> {
    if !table_exists(conn)? {
        return Ok(None);
    }
    let books = conn
        .query_row(&format!("SELECT {COLUMNS} FROM booked_balances WHERE user_channel_id = ?1 AND closed_at IS NULL"), params![uid], read_row)
        .optional()?;
    let Some(books) = books.filter(|books| books.check.state == CheckState::Paused) else {
        return Ok(None);
    };
    conn.execute(
        "UPDATE booked_balances SET booked_user_msat = ?2, check_state = 'ok', gap_direction = NULL, gap_msat = 0,
             gap_first_seen_ms = NULL, gap_sightings = 0, accepted_gap_msat = ?3, accepted_backing_gap_msat = ?4,
             checked_at_ms = NULL, updated_at_ms = ?5, gap_kind = NULL, accepted_shortfall_msat = ?6
         WHERE user_channel_id = ?1",
        params![
            uid,
            to_i64(resolution.new_booked_msat),
            to_i64(resolution.accepted_gap_msat),
            to_i64(resolution.accepted_backing_gap_msat),
            now_ms,
            to_i64(resolution.accepted_shortfall_msat)
        ],
    )?;
    let check = &books.check;
    let channel = resolution.channel;
    let mut detail = serde_json::json!({
        "choice": resolution.choice.as_str(),
        "operator": resolution.operator,
        "reason": resolution.reason,
        "before": {
            "booked_user_msat": books.booked_user_msat,
            "gap_direction": check.direction.map(GapDirection::as_str),
            "gap_msat": check.gap_msat,
            "gap_first_seen_ms": check.first_seen_ms,
            "accepted_gap_msat": check.accepted_gap_msat,
            "accepted_backing_gap_msat": check.accepted_backing_gap_msat,
            "accepted_shortfall_msat": books.accepted_shortfall_msat,
        },
        "after": {
            "booked_user_msat": resolution.new_booked_msat,
            "accepted_gap_msat": resolution.accepted_gap_msat,
            "accepted_backing_gap_msat": resolution.accepted_backing_gap_msat,
            "accepted_shortfall_msat": resolution.accepted_shortfall_msat,
            "expected_usd": channel.map(|(expected_usd, _, _)| expected_usd),
            "backing_sats": channel.map(|(_, backing_sats, _)| backing_sats),
            "native_sats": channel.map(|(_, _, native_sats)| native_sats),
        },
    });
    merge_detail(&mut detail, &resolution.detail);
    let row = draft("BOOKED_BALANCE_RESOLVED", "info", now_ms, None, uid, &books.channel_id, detail);
    let appended = ledger::append_on_connection(conn, &row)?;
    Ok(Some(appended.inserted.then_some((row, appended.event_id))))
}

/// Takes a cut of `cut` off a channel's uncut spends; spends marked after it keep their own overflow.
pub(super) fn clear_uncut(conn: &Connection, user_channel_id: &str, cut: UncutSpends) -> SqliteResult<()> {
    conn.execute(
        "UPDATE booked_balances
         SET uncut_spend_msat = MAX(uncut_spend_msat - ?2, 0), uncut_overflow_msat = MAX(uncut_overflow_msat - ?3, 0)
         WHERE user_channel_id = ?1",
        params![user_channel_id, to_i64(cut.spent_msat), to_i64(cut.overflow_msat)],
    )?;
    Ok(())
}

pub(super) fn mirror(entry: Committed) {
    if let Some((draft, event_id)) = entry {
        crate::audit::mirror_committed_ledger_event(&draft, event_id);
    }
}

impl Database {
    /// Creates the books for a channel; false when an open row already exists.
    pub fn anchor_booked_balance(
        &self,
        uid: u128,
        channel_id: &str,
        user_msat: u64,
        channel_value_sats: u64,
        cursor: ForwardCursor,
        detail: serde_json::Value,
    ) -> SqliteResult<bool> {
        let mut conn = self.conn.lock().unwrap();
        let tx = conn.transaction_with_behavior(TransactionBehavior::Immediate)?;
        create_table(&tx)?;
        let uid = uid.to_string();
        if open_row(&tx, &uid)?.is_some() {
            return Ok(false);
        }
        let now_ms = chrono::Utc::now().timestamp_millis();
        tx.execute(
            "INSERT OR REPLACE INTO booked_balances
                (user_channel_id, channel_id, booked_user_msat, channel_value_sats,
                 fwd_spent_msat, fwd_received_msat, fwd_spent_count, fwd_received_count,
                 anchored_at_ms, updated_at_ms)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?9)",
            params![
                uid,
                channel_id,
                to_i64(user_msat),
                to_i64(channel_value_sats),
                to_i64(cursor.spent_msat),
                to_i64(cursor.received_msat),
                to_i64(cursor.spent_count),
                to_i64(cursor.received_count),
                now_ms
            ],
        )?;
        let mut body = serde_json::json!({
            "booked_user_msat": user_msat,
            "channel_value_sats": channel_value_sats,
            "cursor": cursor_json(&cursor),
        });
        merge_detail(&mut body, &detail);
        let row = draft("BOOKED_BALANCE_ANCHORED", "info", now_ms, None, &uid, channel_id, body);
        let appended = ledger::append_on_connection(&tx, &row)?;
        tx.commit()?;
        mirror(appended.inserted.then_some((row, appended.event_id)));
        Ok(true)
    }

    pub fn booked_balance(&self, uid: u128) -> SqliteResult<Option<BookedBalance>> {
        let conn = self.conn.lock().unwrap();
        if !table_exists(&conn)? {
            return Ok(None);
        }
        conn.query_row(
            &format!("SELECT {COLUMNS} FROM booked_balances WHERE user_channel_id = ?1"),
            params![uid.to_string()],
            read_row,
        )
        .optional()
    }

    /// Open rows only.
    pub fn booked_balances(&self) -> SqliteResult<Vec<BookedBalance>> {
        let conn = self.conn.lock().unwrap();
        if !table_exists(&conn)? {
            return Ok(Vec::new());
        }
        let mut stmt = conn.prepare(&format!(
            "SELECT {COLUMNS} FROM booked_balances WHERE closed_at IS NULL ORDER BY anchored_at_ms, user_channel_id"
        ))?;
        let rows = stmt.query_map([], read_row)?;
        rows.collect()
    }

    /// Books a receipt and saves the channel's native as the books after it less backing, in one transaction; the books after it, or None when it books nothing new, so nothing is saved.
    pub fn book_receipt_with_native(
        &self,
        change: &BookChange,
        channel_id: &str,
        expected_usd: f64,
        backing_sats: u64,
        note: Option<&str>,
    ) -> SqliteResult<Option<u64>> {
        let Some(books) = self.booked_balance(change.user_channel_id)?.filter(|books| books.closed_at.is_none()) else {
            return Ok(None);
        };
        let after_msat = u64::try_from((i128::from(books.booked_user_msat) + i128::from(change.delta_msat)).max(0)).unwrap_or(u64::MAX);
        let native_sats = (after_msat / 1000).saturating_sub(backing_sats);
        let committed: std::cell::RefCell<Committed> = std::cell::RefCell::new(None);
        let skipped = std::cell::Cell::new(false);
        let book = |conn: &Connection| {
            let (outcome, entry) = book_and_capture(conn, change, chrono::Utc::now().timestamp_millis())?;
            // Books that moved since they were read, or a repeated receipt, leave everything as it was.
            if outcome != (BookOutcome::Applied { before_msat: books.booked_user_msat, after_msat }) {
                skipped.set(true);
                return Err(rusqlite::Error::QueryReturnedNoRows);
            }
            *committed.borrow_mut() = entry;
            Ok(())
        };
        let uid = change.user_channel_id.to_string();
        match self.save_channel_inner(channel_id, &uid, expected_usd, backing_sats, native_sats, note, true, false, Some(&book)) {
            Ok(_) => {
                mirror(committed.into_inner());
                Ok(Some(after_msat))
            }
            Err(_) if skipped.get() => Ok(None),
            Err(e) => Err(e),
        }
    }

    /// Whether `reason` under `evidence` is booked on these books.
    pub fn has_booking(&self, uid: u128, reason: BookReason, evidence: &str) -> SqliteResult<bool> {
        let conn = self.conn.lock().unwrap();
        let key = format!("{}{evidence}", key_prefix(&uid.to_string(), reason));
        conn.query_row("SELECT EXISTS(SELECT 1 FROM ledger_events WHERE dedup_key = ?1)", params![key], |row| row.get(0))
    }

    pub fn book(&self, change: &BookChange) -> SqliteResult<BookOutcome> {
        let mut conn = self.conn.lock().unwrap();
        let tx = conn.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let (outcome, entry) = book_and_capture(&tx, change, chrono::Utc::now().timestamp_millis())?;
        tx.commit()?;
        mirror(entry);
        Ok(outcome)
    }

    /// Writes the check columns; a transition, when given, is appended to the ledger in the same transaction.
    pub fn save_books_check(
        &self,
        uid: u128,
        check: &BooksCheck,
        transition: Option<(&'static str, serde_json::Value)>,
    ) -> SqliteResult<()> {
        let mut conn = self.conn.lock().unwrap();
        let tx = conn.transaction_with_behavior(TransactionBehavior::Immediate)?;
        let uid = uid.to_string();
        let Some((channel_id, _)) = open_row(&tx, &uid)? else {
            return Ok(());
        };
        let now_ms = chrono::Utc::now().timestamp_millis();
        tx.execute(
            "UPDATE booked_balances SET check_state = ?2, gap_direction = ?3, gap_msat = ?4,
                 gap_first_seen_ms = ?5, gap_sightings = ?6, accepted_gap_msat = ?7,
                 checked_at_ms = ?8, updated_at_ms = ?9, accepted_backing_gap_msat = ?10, gap_kind = ?11
             WHERE user_channel_id = ?1",
            params![
                uid,
                check.state.as_str(),
                check.direction.map(GapDirection::as_str),
                to_i64(check.gap_msat),
                check.first_seen_ms,
                i64::from(check.sightings),
                to_i64(check.accepted_gap_msat),
                check.checked_at_ms,
                now_ms,
                to_i64(check.accepted_backing_gap_msat),
                check.gap_kind.map(GapKind::as_str)
            ],
        )?;
        let mut entry = None;
        if let Some((event_type, detail)) = transition {
            let severity = if event_type == "BOOKED_BALANCE_MISMATCH" { "warning" } else { "info" };
            let row = draft(event_type, severity, now_ms, None, &uid, &channel_id, detail);
            let appended = ledger::append_on_connection(&tx, &row)?;
            entry = appended.inserted.then_some((row, appended.event_id));
        }
        tx.commit()?;
        mirror(entry);
        Ok(())
    }

    /// Resolves a paused check in one transaction: the books, the check reset to ok with the gaps the resolution accepts, the channel row when given, and the ledger row; false when the books are not paused, so nothing is written.
    pub fn resolve_booked_gap(&self, uid: u128, channel_id: &str, resolution: &GapResolution) -> SqliteResult<bool> {
        let uid = uid.to_string();
        let committed: std::cell::RefCell<Committed> = std::cell::RefCell::new(None);
        let not_paused = std::cell::Cell::new(false);
        let resolve = |conn: &Connection| match resolve_and_capture(conn, &uid, resolution, chrono::Utc::now().timestamp_millis())? {
            Some(entry) => {
                *committed.borrow_mut() = entry;
                Ok(())
            }
            // Books that are not paused leave everything as it was.
            None => {
                not_paused.set(true);
                Err(rusqlite::Error::QueryReturnedNoRows)
            }
        };
        let saved = match resolution.channel {
            // Saved as a spend is, so the undo record of a top-up still on the way moves with the channel.
            Some((expected_usd, backing_sats, native_sats)) => self
                .save_channel_inner(channel_id, &uid, expected_usd, backing_sats, native_sats, resolution.note.as_deref(), true, false, Some(&resolve))
                .map(|_| ()),
            None => {
                let mut conn = self.conn.lock().unwrap();
                let tx = conn.transaction_with_behavior(TransactionBehavior::Immediate)?;
                resolve(&tx).and_then(|()| tx.commit())
            }
        };
        match saved {
            Ok(()) => {
                mirror(committed.into_inner());
                Ok(true)
            }
            Err(_) if not_paused.get() => Ok(false),
            Err(e) => Err(e),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const UID: u128 = 123_456_789_012_345_678_901_234_567_890;

    fn open() -> (tempfile::TempDir, Database) {
        let dir = tempfile::tempdir().unwrap();
        let db = Database::open(dir.path()).unwrap();
        (dir, db)
    }

    fn cursor() -> ForwardCursor {
        ForwardCursor { spent_msat: 7_000, received_msat: 9_000, spent_count: 2, received_count: 3 }
    }

    fn anchor(db: &Database, msat: u64) -> bool {
        db.anchor_booked_balance(UID, "abcd", msat, 100_000, cursor(), serde_json::json!({})).unwrap()
    }

    fn change(delta: i64, evidence: &str) -> BookChange {
        BookChange {
            user_channel_id: UID,
            delta_msat: delta,
            reason: BookReason::LspPayment,
            evidence: evidence.to_owned(),
            cursor: None,
            channel_value_sats: None,
            detail: serde_json::json!({}),
            marks_uncut: false,
            listed_after_msat: None,
        }
    }

    /// A spend the LSP may need to cut, marked uncut as it is booked.
    fn spend(delta: i64, evidence: &str) -> BookChange {
        BookChange { marks_uncut: true, ..change(delta, evidence) }
    }

    fn events(db: &Database, event_type: &str) -> Vec<String> {
        let conn = db.conn.lock().unwrap();
        let mut stmt = conn.prepare("SELECT detail_json FROM ledger_events WHERE event_type = ?1 ORDER BY id").unwrap();
        let rows = stmt.query_map(params![event_type], |row| row.get::<_, String>(0)).unwrap();
        rows.map(Result::unwrap).collect()
    }

    #[test]
    fn anchor_creates_the_row_once() {
        let (_dir, db) = open();
        assert!(anchor(&db, 50_000_000));
        assert!(!anchor(&db, 1));
        let books = db.booked_balance(UID).unwrap().unwrap();
        assert_eq!(books.user_channel_id, UID);
        assert_eq!(books.channel_id, "abcd");
        assert_eq!(books.booked_user_msat, 50_000_000);
        assert_eq!(books.channel_value_sats, 100_000);
        assert_eq!(books.cursor, cursor());
        assert_eq!(books.check, BooksCheck::default());
        assert_eq!(events(&db, "BOOKED_BALANCE_ANCHORED").len(), 1);
    }

    #[test]
    fn a_change_is_booked_once_per_evidence() {
        let (_dir, db) = open();
        anchor(&db, 50_000_000);
        assert_eq!(
            db.book(&change(-10_000, "p1")).unwrap(),
            BookOutcome::Applied { before_msat: 50_000_000, after_msat: 49_990_000 }
        );
        assert_eq!(db.book(&change(-10_000, "p1")).unwrap(), BookOutcome::AlreadyBooked);
        assert_eq!(db.booked_balance(UID).unwrap().unwrap().booked_user_msat, 49_990_000);
        assert_eq!(events(&db, "BOOKED_BALANCE_CHANGED").len(), 1);
    }

    #[test]
    fn a_booking_is_found_by_its_evidence() {
        let (_dir, db) = open();
        anchor(&db, 50_000_000);
        db.book(&spend(-10_000, "p1")).unwrap();
        let booked = |reason: BookReason, evidence: &str| db.has_booking(UID, reason, evidence).unwrap();
        assert_eq!((booked(BookReason::LspPayment, "p1"), booked(BookReason::LspPayment, "p2"), booked(BookReason::TradeFee, "p1")), (true, false, false));
    }

    #[test]
    fn booking_without_books_creates_no_table() {
        let (_dir, db) = open();
        assert_eq!(db.book(&change(-10_000, "p1")).unwrap(), BookOutcome::NotAnchored);
        let conn = db.conn.lock().unwrap();
        assert!(!table_exists(&conn).unwrap());
    }

    #[test]
    fn a_spend_beyond_the_books_stops_at_zero() {
        let (_dir, db) = open();
        anchor(&db, 1_000);
        assert_eq!(
            db.book(&change(-5_000, "p1")).unwrap(),
            BookOutcome::Applied { before_msat: 1_000, after_msat: 0 }
        );
        let detail: serde_json::Value = serde_json::from_str(&events(&db, "BOOKED_BALANCE_CHANGED")[0]).unwrap();
        assert_eq!(detail["clamped_msat"], 4_000);
        assert_eq!(db.booked_balance(UID).unwrap().unwrap().booked_user_msat, 0);
    }

    #[test]
    fn the_check_state_is_kept_and_only_transitions_write_rows() {
        let (_dir, db) = open();
        anchor(&db, 50_000_000);
        let watching = BooksCheck {
            state: CheckState::Watching,
            direction: Some(GapDirection::Below),
            gap_msat: 12_000,
            first_seen_ms: Some(1_000),
            sightings: 1,
            accepted_gap_msat: 0,
            accepted_backing_gap_msat: 0,
            checked_at_ms: Some(2_000),
            gap_kind: Some(GapKind::Balance),
        };
        db.save_books_check(UID, &watching, None).unwrap();
        assert_eq!(db.booked_balance(UID).unwrap().unwrap().check, watching);
        assert!(events(&db, "BOOKED_BALANCE_MISMATCH").is_empty());
        let paused = BooksCheck { state: CheckState::Paused, sightings: 3, accepted_gap_msat: 500, accepted_backing_gap_msat: 700, gap_kind: Some(GapKind::Backing), ..watching };
        db.save_books_check(UID, &paused, Some(("BOOKED_BALANCE_MISMATCH", serde_json::json!({"gap_msat": 12_000}))))
            .unwrap();
        assert_eq!(db.booked_balance(UID).unwrap().unwrap().check, paused);
        assert_eq!(events(&db, "BOOKED_BALANCE_MISMATCH").len(), 1);
    }

    #[test]
    fn a_closed_channel_books_nothing_more() {
        let (_dir, db) = open();
        db.save_channel("abcd", &UID.to_string(), 10.0, 1_000, 0, None).unwrap();
        anchor(&db, 50_000_000);
        db.mark_channel_closed(&UID.to_string()).unwrap();
        assert_eq!(db.book(&change(-10_000, "p1")).unwrap(), BookOutcome::NotAnchored);
        assert!(db.booked_balances().unwrap().is_empty());
        let closed_at = db.booked_balance(UID).unwrap().unwrap().closed_at.unwrap();
        let channel_closed_at: i64 = db
            .conn
            .lock()
            .unwrap()
            .query_row("SELECT closed_at FROM channels WHERE user_channel_id = ?1", params![UID.to_string()], |row| row.get(0))
            .unwrap();
        assert!((closed_at - channel_closed_at).abs() <= 2);
        assert!((closed_at - chrono::Utc::now().timestamp()).abs() <= 5);
        assert_eq!(events(&db, "BOOKED_BALANCE_CLOSED").len(), 1);
    }

    #[test]
    fn a_channel_missing_from_a_listing_keeps_its_books_until_it_really_closes() {
        let (_dir, db) = open();
        db.save_channel("abcd", &UID.to_string(), 10.0, 1_000, 0, None).unwrap();
        anchor(&db, 50_000_000);
        db.mark_unlisted_channel_closed(&UID.to_string()).unwrap();
        assert!(db.load_all_channels().unwrap().is_empty(), "the channel row is soft-closed");
        assert_eq!(db.booked_balance(UID).unwrap().unwrap().closed_at, None);
        // The real close arrives after the row is already soft-closed.
        db.mark_channel_closed(&UID.to_string()).unwrap();
        assert!(db.booked_balance(UID).unwrap().unwrap().closed_at.is_some());
        db.mark_channel_closed(&UID.to_string()).unwrap();
        assert_eq!(events(&db, "BOOKED_BALANCE_CLOSED").len(), 1);
    }

    #[test]
    fn the_books_survive_reopening_the_database() {
        let dir = tempfile::tempdir().unwrap();
        let before = {
            let db = Database::open(dir.path()).unwrap();
            anchor(&db, 50_000_000);
            db.book(&change(-10_000, "p1")).unwrap();
            db.booked_balance(UID).unwrap().unwrap()
        };
        let db = Database::open(dir.path()).unwrap();
        assert_eq!(db.booked_balance(UID).unwrap().unwrap(), before);
    }

    #[test]
    fn books_made_before_uncut_spends_gain_the_columns_on_open() {
        let dir = tempfile::tempdir().unwrap();
        {
            let db = Database::open(dir.path()).unwrap();
            let conn = db.conn.lock().unwrap();
            assert!(!table_exists(&conn).unwrap(), "opening makes no books table");
            // The table as the first books release made it, with one channel's books.
            conn.execute_batch(
                "CREATE TABLE booked_balances (
                    user_channel_id TEXT PRIMARY KEY, channel_id TEXT NOT NULL, booked_user_msat INTEGER NOT NULL,
                    channel_value_sats INTEGER NOT NULL, fwd_spent_msat INTEGER NOT NULL, fwd_received_msat INTEGER NOT NULL,
                    fwd_spent_count INTEGER NOT NULL, fwd_received_count INTEGER NOT NULL, check_state TEXT NOT NULL DEFAULT 'ok',
                    gap_direction TEXT, gap_msat INTEGER NOT NULL DEFAULT 0, gap_first_seen_ms INTEGER,
                    gap_sightings INTEGER NOT NULL DEFAULT 0, accepted_gap_msat INTEGER NOT NULL DEFAULT 0, checked_at_ms INTEGER,
                    anchored_at_ms INTEGER NOT NULL, updated_at_ms INTEGER NOT NULL, closed_at INTEGER
                );
                INSERT INTO booked_balances (user_channel_id, channel_id, booked_user_msat, channel_value_sats,
                    fwd_spent_msat, fwd_received_msat, fwd_spent_count, fwd_received_count, anchored_at_ms, updated_at_ms)
                VALUES ('123456789012345678901234567890', 'abcd', 50000000, 100000, 7000, 9000, 2, 3, 1764000000000, 1764000000000);",
            )
            .unwrap();
        }
        // Opened twice, so a column already added is no error.
        for _ in 0..2 {
            let db = Database::open(dir.path()).unwrap();
            let books = db.booked_balance(UID).unwrap().unwrap();
            assert_eq!((books.booked_user_msat, books.uncut, books.check.accepted_backing_gap_msat, books.check.gap_kind, books.accepted_shortfall_msat), (50_000_000, None, 0, None, 0));
        }
        let db = Database::open(dir.path()).unwrap();
        db.save_channel("abcd", &UID.to_string(), 45.0, 45_000, 5_000, None).unwrap();
        db.book(&spend(-10_000_000, "after-upgrade")).unwrap();
        assert_eq!(db.booked_balance(UID).unwrap().unwrap().uncut, Some(UncutSpends { spent_msat: 10_000_000, overflow_msat: 5_000_000 }));
    }

    #[test]
    fn the_books_table_holds_only_the_columns_the_books_use() {
        let columns = |conn: &Connection| -> std::collections::BTreeSet<String> {
            let mut stmt = conn.prepare("PRAGMA table_info(booked_balances)").unwrap();
            let names = stmt.query_map([], |row| row.get::<_, String>(1)).unwrap();
            names.map(Result::unwrap).collect()
        };
        let used: std::collections::BTreeSet<String> = COLUMNS.split(',').map(|name| name.trim().to_owned()).chain(["anchored_at_ms".to_owned(), "updated_at_ms".to_owned()]).collect();
        let db = Database::open_in_memory().unwrap();
        anchor(&db, 50_000_000);
        assert_eq!(columns(&db.conn.lock().unwrap()), used, "made new");
        // The table as the first books release made it, upgraded on open.
        let dir = tempfile::tempdir().unwrap();
        Database::open(dir.path()).unwrap().conn.lock().unwrap().execute_batch(
            "CREATE TABLE booked_balances (
                user_channel_id TEXT PRIMARY KEY, channel_id TEXT NOT NULL, booked_user_msat INTEGER NOT NULL,
                channel_value_sats INTEGER NOT NULL, fwd_spent_msat INTEGER NOT NULL, fwd_received_msat INTEGER NOT NULL,
                fwd_spent_count INTEGER NOT NULL, fwd_received_count INTEGER NOT NULL, check_state TEXT NOT NULL DEFAULT 'ok',
                gap_direction TEXT, gap_msat INTEGER NOT NULL DEFAULT 0, gap_first_seen_ms INTEGER,
                gap_sightings INTEGER NOT NULL DEFAULT 0, accepted_gap_msat INTEGER NOT NULL DEFAULT 0, checked_at_ms INTEGER,
                anchored_at_ms INTEGER NOT NULL, updated_at_ms INTEGER NOT NULL, closed_at INTEGER
            );",
        ).unwrap();
        assert_eq!(columns(&Database::open(dir.path()).unwrap().conn.lock().unwrap()), used, "upgraded");
    }

    /// Books as the first release made them, with `uid`'s check paused by it.
    fn books_paused_by_the_first_release(dir: &std::path::Path, uid: u128) {
        let db = Database::open(dir).unwrap();
        db.conn.lock().unwrap().execute("DELETE FROM ledger_metadata WHERE key = ?1", [PAUSE_RESET_KEY]).unwrap();
        db.anchor_booked_balance(uid, "e3b6a9c2f5d8e1b4a7c0f3d6e9b2a5c8f1d4e7a0b3c6f9e2d5a8b1c4f7e0a3d6", 50_000_000, 100_000, cursor(), serde_json::json!({})).unwrap();
        let paused = BooksCheck { state: CheckState::Paused, direction: Some(GapDirection::Below), gap_msat: 20_000_000, first_seen_ms: Some(1_764_000_000_000), sightings: 9, accepted_gap_msat: 0, accepted_backing_gap_msat: 0, checked_at_ms: Some(1_764_000_600_000), gap_kind: None };
        db.save_books_check(uid, &paused, None).unwrap();
    }

    #[test]
    fn a_log_only_pause_is_watched_again_once_after_the_upgrade() {
        let dir = tempfile::tempdir().unwrap();
        books_paused_by_the_first_release(dir.path(), UID);
        let db = Database::open(dir.path()).unwrap();
        let check = db.booked_balance(UID).unwrap().unwrap().check;
        assert_eq!((check.state, check.direction, check.sightings, check.gap_msat), (CheckState::Watching, Some(GapDirection::Below), 1, 20_000_000));
        assert_eq!(check.first_seen_ms, Some(1_764_000_000_000), "since when is kept");
        // A pause made after the reset survives every later start.
        let paused = BooksCheck { state: CheckState::Paused, sightings: 2, ..check };
        db.save_books_check(UID, &paused, None).unwrap();
        drop(db);
        let db = Database::open(dir.path()).unwrap();
        assert_eq!(db.booked_balance(UID).unwrap().unwrap().check, paused);
    }

    #[test]
    fn the_reset_of_a_log_only_pause_is_recorded_once() {
        let dir = tempfile::tempdir().unwrap();
        let unpaused: u128 = 98_765_432_109_876_543_210_987_654_321;
        Database::open(dir.path()).unwrap().anchor_booked_balance(unpaused, "c4f7a0d3e6b9c2f5a8d1e4b7c0f3a6d9e2b5c8f1a4d7e0b3c6f9a2d5e8b1c4f7", 40_000_000, 100_000, cursor(), serde_json::json!({})).unwrap();
        books_paused_by_the_first_release(dir.path(), UID);
        let db = Database::open(dir.path()).unwrap();
        let rows = events(&db, "BOOKED_BALANCE_CHECK_RESET");
        assert_eq!(rows.len(), 1, "only the paused channel");
        let row: serde_json::Value = serde_json::from_str(&rows[0]).unwrap();
        assert_eq!((&row["from"], &row["to"], &row["reason"]), (&serde_json::json!("paused"), &serde_json::json!("watching"), &serde_json::json!("log_only_pause")));
        assert_eq!(row["user_channel_id"], UID.to_string());
        let conn = db.conn.lock().unwrap();
        let refs: Vec<(String, String)> = conn
            .prepare("SELECT r.role, r.value FROM ledger_event_refs r JOIN ledger_events e ON e.id = r.event_id WHERE e.event_type = 'BOOKED_BALANCE_CHECK_RESET' ORDER BY r.role")
            .unwrap()
            .query_map([], |row| Ok((row.get(0)?, row.get(1)?)))
            .unwrap()
            .map(Result::unwrap)
            .collect();
        drop(conn);
        assert!(refs.contains(&("user_channel_id".to_owned(), UID.to_string())), "{refs:?}");
        drop(db);
        let db = Database::open(dir.path()).unwrap();
        assert_eq!(events(&db, "BOOKED_BALANCE_CHECK_RESET").len(), 1, "a second start writes nothing");
    }

    #[test]
    fn a_database_first_opened_after_the_upgrade_never_resets_a_pause() {
        let dir = tempfile::tempdir().unwrap();
        let db = Database::open(dir.path()).unwrap();
        assert!(!table_exists(&db.conn.lock().unwrap()).unwrap(), "no books yet");
        anchor(&db, 50_000_000);
        let paused = BooksCheck { state: CheckState::Paused, direction: Some(GapDirection::Below), gap_msat: 7_000_000, first_seen_ms: Some(1_764_100_000_000), sightings: 2, accepted_gap_msat: 0, accepted_backing_gap_msat: 0, checked_at_ms: Some(1_764_100_120_000), gap_kind: None };
        db.save_books_check(UID, &paused, None).unwrap();
        drop(db);
        let db = Database::open(dir.path()).unwrap();
        assert_eq!(db.booked_balance(UID).unwrap().unwrap().check, paused);
    }

    #[test]
    fn a_receipt_and_its_native_are_written_together() {
        let (_dir, db) = open();
        let receipt = BookChange { delta_msat: 2_150_000, ..change(0, "refund-7c3e") };
        assert_eq!(db.book_receipt_with_native(&receipt, "abcd", 40.0, 40_000, None).unwrap(), None, "no books, nothing saved");
        db.save_channel("abcd", &UID.to_string(), 40.0, 40_000, 0, None).unwrap();
        anchor(&db, 40_000_000);
        assert_eq!(db.book_receipt_with_native(&receipt, "abcd", 40.0, 40_000, None).unwrap(), Some(42_150_000));
        let row = db.load_channel(&UID.to_string()).unwrap().unwrap();
        assert_eq!((row.backing_sats, row.native_sats), (40_000, 2_150));
        // The same receipt again books and saves nothing.
        db.save_channel("abcd", &UID.to_string(), 40.0, 40_000, 7, None).unwrap();
        assert_eq!(db.book_receipt_with_native(&receipt, "abcd", 40.0, 40_000, None).unwrap(), None);
        assert_eq!(db.load_channel(&UID.to_string()).unwrap().unwrap().native_sats, 7);
        assert_eq!(db.booked_balance(UID).unwrap().unwrap().booked_user_msat, 42_150_000);
    }

    #[test]
    fn each_marked_spend_keeps_the_overflow_it_was_booked_with_and_a_cut_takes_off_only_its_own() {
        let (_dir, db) = open();
        assert_eq!(db.book(&spend(-10_000_000, "first")).unwrap(), BookOutcome::NotAnchored, "no books, nothing marked");
        // 45,000 of the user's 50,000 sats back the target, so 5,000 are spare.
        db.save_channel("abcd", &UID.to_string(), 45.0, 45_000, 5_000, None).unwrap();
        anchor(&db, 50_000_000);
        db.book(&spend(-10_000_000, "first")).unwrap();
        assert_eq!(db.booked_balance(UID).unwrap().unwrap().uncut, Some(UncutSpends { spent_msat: 10_000_000, overflow_msat: 5_000_000 }));
        // A receipt between the spends is spare BTC for the second, weighed against the backing the first will leave.
        db.book(&spend(3_000_000, "receipt")).unwrap();
        db.book(&spend(-5_000_000, "second")).unwrap();
        let both = Some(UncutSpends { spent_msat: 15_000_000, overflow_msat: 7_000_000 });
        assert_eq!(db.booked_balance(UID).unwrap().unwrap().uncut, both);
        // Backing that moves later never changes what is marked.
        db.save_channel("abcd", &UID.to_string(), 50.0, 50_000, 0, None).unwrap();
        assert_eq!(db.booked_balance(UID).unwrap().unwrap().uncut, both);
        // The same evidence again, and a spend booked without the mark, add nothing to it.
        assert_eq!(db.book(&spend(-5_000_000, "second")).unwrap(), BookOutcome::AlreadyBooked);
        db.book(&change(-1_000, "unmarked")).unwrap();
        assert_eq!(db.booked_balance(UID).unwrap().unwrap().uncut, both);
        let rows = events(&db, "BOOKED_BALANCE_CHANGED");
        assert_eq!(rows.len(), 4);
        let row = |index: usize| serde_json::from_str::<serde_json::Value>(&rows[index]).unwrap();
        let marked = |index: usize| ["uncut_spend_msat", "uncut_overflow_msat", "overflow_msat", "overflow_balance_before_msat", "overflow_backing_sats"].map(|name| row(index)[name].clone());
        assert_eq!(marked(0), [10_000_000, 5_000_000, 5_000_000, 50_000_000, 45_000].map(|figure| serde_json::json!(figure)));
        assert_eq!(marked(2), [15_000_000, 7_000_000, 2_000_000, 43_000_000, 40_000].map(|figure| serde_json::json!(figure)));
        assert!(!rows[1].contains("uncut_spend_msat") && !rows[3].contains("uncut_spend_msat"), "{rows:?}");

        // A save that fails takes nothing off.
        let first = UncutSpends { spent_msat: 10_000_000, overflow_msat: 5_000_000 };
        db.conn.lock().unwrap().execute_batch("CREATE TRIGGER refuse_saves BEFORE UPDATE ON channels BEGIN SELECT RAISE(ABORT, 'refused'); END;").unwrap();
        assert!(db.save_channel_after_booked_spend("abcd", &UID.to_string(), 45.0, 45_000, 0, None, first).is_err());
        assert_eq!(db.booked_balance(UID).unwrap().unwrap().uncut, both);
        db.conn.lock().unwrap().execute_batch("DROP TRIGGER refuse_saves;").unwrap();

        // Cutting the first spend alone leaves the second with its own overflow.
        db.save_channel_after_booked_spend("abcd", &UID.to_string(), 45.0, 45_000, 0, None, first).unwrap();
        assert_eq!(db.booked_balance(UID).unwrap().unwrap().uncut, Some(UncutSpends { spent_msat: 5_000_000, overflow_msat: 2_000_000 }));
        db.save_channel_after_booked_spend("abcd", &UID.to_string(), 43.0, 43_000, 0, None, UncutSpends { spent_msat: 5_000_000, overflow_msat: 2_000_000 }).unwrap();
        assert_eq!(db.booked_balance(UID).unwrap().unwrap().uncut, None);
    }

    #[test]
    fn a_spend_marked_while_the_books_show_a_gap_below_is_weighed_from_the_smaller_balance() {
        let (_dir, db) = open();
        // 10,000 of the user's 50,000 booked sats back the target, but the listing after a 10,000-sat spend shows only 5,000 left.
        db.save_channel("abcd", &UID.to_string(), 10.0, 10_000, 40_000, None).unwrap();
        anchor(&db, 50_000_000);
        let listed = |evidence: &str| BookChange { listed_after_msat: Some(5_000_000), ..spend(-10_000_000, evidence) };
        // With no gap below the books decide, and their spare BTC covers it.
        db.book(&listed("ok")).unwrap();
        assert_eq!(db.booked_balance(UID).unwrap().unwrap().uncut, Some(UncutSpends { spent_msat: 10_000_000, overflow_msat: 0 }));
        db.save_channel_after_booked_spend("abcd", &UID.to_string(), 10.0, 10_000, 30_000, None, UncutSpends { spent_msat: 10_000_000, overflow_msat: 0 }).unwrap();
        // Paused, the balance before the spend is the 15,000 the listing gives, so 5,000 sats come out of the stable part.
        pause(&db, 25_000_000);
        db.book(&listed("paused")).unwrap();
        assert_eq!(db.booked_balance(UID).unwrap().unwrap().uncut, Some(UncutSpends { spent_msat: 10_000_000, overflow_msat: 5_000_000 }));
        // A listing that could not be read leaves the books to decide.
        db.book(&spend(-10_000_000, "unread")).unwrap();
        assert_eq!(db.booked_balance(UID).unwrap().unwrap().uncut, Some(UncutSpends { spent_msat: 20_000_000, overflow_msat: 5_000_000 }));
    }

    #[test]
    fn a_spend_after_a_kept_gap_is_weighed_without_it_in_every_state() {
        let (_dir, db) = open();
        // 40,000 of the user's 50,000 booked sats back the target, and the operator kept a 10,000-sat gap below them.
        db.save_channel("abcd", &UID.to_string(), 40.0, 40_000, 10_000, None).unwrap();
        anchor(&db, 50_000_000);
        pause(&db, 10_000_000);
        let keep = GapResolution { accepted_gap_msat: 10_000_000, ..resolution(GapChoice::Keep, 50_000_000, None) };
        assert!(db.resolve_booked_gap(UID, "abcd", &keep).unwrap());
        // The 10,000 spare the books show are the gap kept, so a 7,500-sat spend comes all out of the stable part.
        db.book(&spend(-7_500_000, "kept")).unwrap();
        let kept = UncutSpends { spent_msat: 7_500_000, overflow_msat: 7_500_000 };
        assert_eq!(db.booked_balance(UID).unwrap().unwrap().uncut, Some(kept));
        db.save_channel_after_booked_spend("abcd", &UID.to_string(), 32.5, 32_500, 10_000, None, kept).unwrap();
        // Watching a gap below again with the gap still kept: a listing of 35,000 sats before a 5,000-sat spend, above the books less the gap kept, cannot raise the 32,500 the spend is weighed from.
        let watching = BooksCheck { state: CheckState::Watching, direction: Some(GapDirection::Below), gap_msat: 2_500_000, sightings: 1, accepted_gap_msat: 10_000_000, gap_kind: Some(GapKind::Backing), ..Default::default() };
        db.save_books_check(UID, &watching, None).unwrap();
        db.book(&BookChange { listed_after_msat: Some(30_000_000), ..spend(-5_000_000, "listed") }).unwrap();
        let listed = db.booked_balance(UID).unwrap().unwrap();
        assert_eq!((listed.check.accepted_gap_msat, listed.uncut), (10_000_000, Some(UncutSpends { spent_msat: 5_000_000, overflow_msat: 5_000_000 })));
        // Without a listing the books decide, less the gap kept: 27,500 against the 27,500 of backing the marked spend leaves.
        db.book(&spend(-1_000_000, "unread")).unwrap();
        assert_eq!(db.booked_balance(UID).unwrap().unwrap().uncut, Some(UncutSpends { spent_msat: 6_000_000, overflow_msat: 6_000_000 }));
        let rows = events(&db, "BOOKED_BALANCE_CHANGED");
        let weighed: Vec<_> = rows.iter().map(|row| serde_json::from_str::<serde_json::Value>(row).unwrap()["overflow_balance_before_msat"].clone()).collect();
        assert_eq!(weighed, [40_000_000, 32_500_000, 27_500_000].map(|msat| serde_json::json!(msat)));
    }

    /// Pauses the check on a gap below of `gap_msat`.
    fn pause(db: &Database, gap_msat: u64) {
        let paused = BooksCheck { state: CheckState::Paused, direction: Some(GapDirection::Below), gap_msat, first_seen_ms: Some(1_764_200_000_000), sightings: 2, checked_at_ms: Some(1_764_200_060_000), gap_kind: Some(GapKind::Backing), ..Default::default() };
        db.save_books_check(UID, &paused, None).unwrap();
    }

    fn resolution(choice: GapChoice, new_booked_msat: u64, channel: Option<(f64, u64, u64)>) -> GapResolution {
        GapResolution {
            choice,
            new_booked_msat,
            accepted_gap_msat: 0,
            accepted_backing_gap_msat: 0,
            accepted_shortfall_msat: 0,
            channel,
            note: Some("met at the meetup".to_owned()),
            operator: "operator".to_owned(),
            reason: Some("the wallet's history shows the spend".to_owned()),
            detail: serde_json::json!({ "actual_msat": 30_000_000 }),
        }
    }

    fn resolved_rows(db: &Database) -> Vec<serde_json::Value> {
        events(db, "BOOKED_BALANCE_RESOLVED").iter().map(|row| serde_json::from_str(row).unwrap()).collect()
    }

    #[test]
    fn a_resolution_resets_a_paused_check_once() {
        let (_dir, db) = open();
        let spend = resolution(GapChoice::Spend, 30_000_000, Some((30.0, 30_000, 0)));
        assert!(!db.resolve_booked_gap(UID, "abcd", &spend).unwrap(), "no books, nothing to resolve");
        db.save_channel("abcd", &UID.to_string(), 50.0, 50_000, 0, Some("met at the meetup")).unwrap();
        anchor(&db, 50_000_000);
        assert!(!db.resolve_booked_gap(UID, "abcd", &spend).unwrap(), "books that are not paused");
        pause(&db, 20_000_000);
        assert_eq!(db.booked_balance(UID).unwrap().unwrap().check.gap_kind, Some(GapKind::Backing));
        assert!(db.resolve_booked_gap(UID, "abcd", &spend).unwrap());
        let books = db.booked_balance(UID).unwrap().unwrap();
        assert_eq!((books.booked_user_msat, books.check), (30_000_000, BooksCheck::default()));
        let row = db.load_channel(&UID.to_string()).unwrap().unwrap();
        assert_eq!((row.expected_usd, row.backing_sats, row.native_sats, row.note.as_deref()), (30.0, 30_000, 0, Some("met at the meetup")));
        let rows = resolved_rows(&db);
        assert_eq!(rows.len(), 1);
        assert_eq!((&rows[0]["choice"], &rows[0]["operator"], &rows[0]["reason"]), (&"spend".into(), &"operator".into(), &"the wallet's history shows the spend".into()));
        assert_eq!((&rows[0]["before"]["booked_user_msat"], &rows[0]["before"]["gap_msat"]), (&50_000_000.into(), &20_000_000.into()));
        assert_eq!((&rows[0]["after"]["booked_user_msat"], &rows[0]["after"]["backing_sats"], &rows[0]["actual_msat"]), (&30_000_000.into(), &30_000.into(), &30_000_000.into()));
        assert!(!db.resolve_booked_gap(UID, "abcd", &spend).unwrap(), "a second resolution finds nothing paused");
        assert_eq!(resolved_rows(&db).len(), 1);
    }

    #[test]
    fn keeping_the_books_accepts_the_gaps_and_leaves_the_channel_row() {
        let dir = tempfile::tempdir().unwrap();
        let db = Database::open(dir.path()).unwrap();
        db.save_channel("abcd", &UID.to_string(), 50.0, 50_000, 0, None).unwrap();
        anchor(&db, 50_000_000);
        pause(&db, 20_000_000);
        let keep = GapResolution { accepted_gap_msat: 20_000_000, accepted_backing_gap_msat: 7_000_000, accepted_shortfall_msat: 27_000_000, ..resolution(GapChoice::Keep, 50_000_000, None) };
        assert!(db.resolve_booked_gap(UID, "abcd", &keep).unwrap());
        drop(db);
        let db = Database::open(dir.path()).unwrap();
        let books = db.booked_balance(UID).unwrap().unwrap();
        assert_eq!((books.booked_user_msat, books.check.state, books.check.direction), (50_000_000, CheckState::Ok, None));
        assert_eq!((books.check.accepted_gap_msat, books.check.accepted_backing_gap_msat, books.accepted_shortfall_msat), (20_000_000, 7_000_000, 27_000_000));
        let row = db.load_channel(&UID.to_string()).unwrap().unwrap();
        assert_eq!((row.expected_usd, row.backing_sats, row.native_sats), (50.0, 50_000, 0));
        assert_eq!(resolved_rows(&db)[0]["after"]["accepted_backing_gap_msat"], 7_000_000);
        assert_eq!(resolved_rows(&db)[0]["after"]["accepted_shortfall_msat"], 27_000_000);
    }

    #[test]
    fn a_resolution_whose_record_is_refused_writes_nothing() {
        let (_dir, db) = open();
        db.save_channel("abcd", &UID.to_string(), 50.0, 50_000, 0, None).unwrap();
        anchor(&db, 50_000_000);
        pause(&db, 20_000_000);
        let before = db.booked_balance(UID).unwrap().unwrap();
        db.conn.lock().unwrap().execute_batch("CREATE TRIGGER refuse_resolved BEFORE INSERT ON ledger_events WHEN NEW.event_type = 'BOOKED_BALANCE_RESOLVED' BEGIN SELECT RAISE(ABORT, 'refused'); END;").unwrap();
        for channel in [Some((30.0, 30_000, 0)), None] {
            assert!(db.resolve_booked_gap(UID, "abcd", &resolution(GapChoice::Spend, 30_000_000, channel)).is_err());
            assert_eq!(db.booked_balance(UID).unwrap().unwrap(), before);
            let row = db.load_channel(&UID.to_string()).unwrap().unwrap();
            assert_eq!((row.expected_usd, row.backing_sats, row.native_sats), (50.0, 50_000, 0));
        }
    }
}
