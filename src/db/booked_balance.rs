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

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct BooksCheck {
    pub state: CheckState,
    pub direction: Option<GapDirection>,
    pub gap_msat: u64,
    pub first_seen_ms: Option<i64>,
    pub sightings: u8,
    pub accepted_gap_msat: u64,
    pub checked_at_ms: Option<i64>,
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
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BookOutcome {
    Applied { before_msat: u64, after_msat: u64 },
    AlreadyBooked,
    NotAnchored,
}

/// A ledger row appended in a transaction, to be mirrored to the audit log after the commit.
type Committed = Option<(LedgerEventDraft, i64)>;

const COLUMNS: &str = "user_channel_id, channel_id, booked_user_msat, channel_value_sats,
    fwd_spent_msat, fwd_received_msat, fwd_spent_count, fwd_received_count,
    check_state, gap_direction, gap_msat, gap_first_seen_ms, gap_sightings, accepted_gap_msat,
    checked_at_ms, closed_at";

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
            closed_at INTEGER
        );",
    )
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
            checked_at_ms: row.get(14)?,
        },
        closed_at: row.get(15)?,
    })
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
    let key = format!("{}{}", key_prefix(&uid, change.reason), change.evidence);
    let row = draft("BOOKED_BALANCE_CHANGED", "info", now_ms, Some(key), &uid, &channel_id, detail);
    let appended = ledger::append_on_connection(conn, &row)?;
    if !appended.inserted {
        return Ok((BookOutcome::AlreadyBooked, None));
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
                 checked_at_ms = ?8, updated_at_ms = ?9
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
                now_ms
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
        }
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
            checked_at_ms: Some(2_000),
        };
        db.save_books_check(UID, &watching, None).unwrap();
        assert_eq!(db.booked_balance(UID).unwrap().unwrap().check, watching);
        assert!(events(&db, "BOOKED_BALANCE_MISMATCH").is_empty());
        let paused = BooksCheck { state: CheckState::Paused, sightings: 3, accepted_gap_msat: 500, ..watching };
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
}
