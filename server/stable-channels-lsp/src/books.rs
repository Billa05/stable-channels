//! The LSP's booked user balance per channel: LDK's forwarding totals, booking forwards and catch-ups, and the check against the live balance.

use std::collections::HashMap;

use ldk_server_client::error::{LdkServerError, LdkServerErrorCode};
use ldk_server_client::ldk_server_grpc::api::ListChannelForwardingStatsRequest;
use ldk_server_client::ldk_server_grpc::types::ChannelForwardingStats;
use ldk_server_client::ldk_server_grpc::types::Channel;
use stable_channels::db::{BooksCheck, CheckState, ForwardCursor, GapDirection};

use crate::stable_manager::LdkServerCalls;

/// LDK's inbound leg is what the user spent and its outbound leg is what the user received.
pub fn cursor_from_stats(stats: &ChannelForwardingStats) -> ForwardCursor {
    ForwardCursor {
        spent_msat: stats.total_inbound_amount_msat,
        received_msat: stats.total_outbound_amount_msat,
        spent_count: stats.inbound_payments_forwarded,
        received_count: stats.outbound_payments_forwarded,
    }
}

/// More pages than this means the listing does not end.
const MAX_FORWARDING_STATS_PAGES: usize = 1_000;

/// Every channel's forwarding totals by channel id, read page by page.
pub async fn forwarding_totals(
    ldk: &dyn LdkServerCalls,
) -> Result<HashMap<String, ForwardCursor>, LdkServerError> {
    let mut totals = HashMap::new();
    let mut page_token: Option<String> = None;
    for _ in 0..MAX_FORWARDING_STATS_PAGES {
        let page = ldk.list_channel_forwarding_stats(ListChannelForwardingStatsRequest { page_token: page_token.clone() }).await?;
        for stats in &page.stats {
            totals.insert(stats.channel_id.clone(), cursor_from_stats(stats));
        }
        match page.next_page_token {
            None => return Ok(totals),
            Some(token) if page_token.as_ref() == Some(&token) => {
                return Err(LdkServerError::new(LdkServerErrorCode::InternalError, "forwarding stats page token repeated"));
            }
            Some(token) => page_token = Some(token),
        }
    }
    Err(LdkServerError::new(LdkServerErrorCode::InternalError, "forwarding stats listing did not end"))
}

pub(crate) const BOOKS_TOLERANCE_MSAT: u64 = 5_000;
pub(crate) const BOOKS_SIGHTINGS_TO_PAUSE: u8 = 2;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum ForwardSide {
    OutOfUser,
    IntoUser,
}

/// The msat to book for a forward event and the cursor after it.
pub(crate) fn book_forward_event(
    cursor: ForwardCursor,
    totals: Option<ForwardCursor>,
    side: ForwardSide,
    amount_msat: u64,
    counted_by_ldk: bool,
) -> (u64, ForwardCursor) {
    if !counted_by_ldk {
        return (amount_msat, cursor);
    }
    let (cur_msat, cur_count, tot) = match side {
        ForwardSide::OutOfUser => (cursor.spent_msat, cursor.spent_count, totals.map(|t| (t.spent_msat, t.spent_count))),
        ForwardSide::IntoUser => (cursor.received_msat, cursor.received_count, totals.map(|t| (t.received_msat, t.received_count))),
    };
    let (booked, new_msat, new_count) = match tot {
        Some((tot_msat, tot_count)) => {
            let booked = amount_msat.min(tot_msat.saturating_sub(cur_msat));
            let count = if booked > 0 { cur_count.saturating_add(1).min(tot_count).max(cur_count) } else { cur_count };
            (booked, cur_msat.saturating_add(booked), count)
        }
        None => (amount_msat, cur_msat.saturating_add(amount_msat), cur_count.saturating_add(1)),
    };
    let mut next = cursor;
    match side {
        ForwardSide::OutOfUser => {
            next.spent_msat = new_msat;
            next.spent_count = new_count;
        }
        ForwardSide::IntoUser => {
            next.received_msat = new_msat;
            next.received_count = new_count;
        }
    }
    (booked, next)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct CatchUp {
    pub spent_msat: u64,
    pub received_msat: u64,
    pub net_msat: i64,
    pub cursor: ForwardCursor,
}

/// What LDK counted since the cursor, leaving queued forwards for their events.
pub(crate) fn catch_up(cursor: ForwardCursor, totals: ForwardCursor, queued: ForwardCursor) -> Option<CatchUp> {
    let target = ForwardCursor {
        spent_msat: totals.spent_msat.saturating_sub(queued.spent_msat).max(cursor.spent_msat),
        received_msat: totals.received_msat.saturating_sub(queued.received_msat).max(cursor.received_msat),
        spent_count: totals.spent_count.saturating_sub(queued.spent_count).max(cursor.spent_count),
        received_count: totals.received_count.saturating_sub(queued.received_count).max(cursor.received_count),
    };
    let spent = target.spent_msat - cursor.spent_msat;
    let received = target.received_msat - cursor.received_msat;
    if spent == 0 && received == 0 {
        return None;
    }
    let net = (received as i128 - spent as i128).clamp(i64::MIN as i128, i64::MAX as i128) as i64;
    Some(CatchUp { spent_msat: spent, received_msat: received, net_msat: net, cursor: target })
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct LiveUserBalance {
    pub low_msat: u64,
    pub high_msat: u64,
}

/// The user's balance from the channel, a range while it sits inside the reserve.
pub(crate) fn live_user_balance(channel: &Channel, held_msat: u64) -> LiveUserBalance {
    let reserve = channel.counterparty_unspendable_punishment_reserve.saturating_mul(1000);
    if channel.inbound_capacity_msat > 0 {
        let v = channel.inbound_capacity_msat.saturating_add(reserve).saturating_add(held_msat);
        LiveUserBalance { low_msat: v, high_msat: v }
    } else {
        LiveUserBalance { low_msat: held_msat, high_msat: reserve.saturating_add(held_msat) }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Transition {
    Watching,
    Paused,
    LastingAbove { excess_msat: u64 },
    Cleared,
}

/// One tick's check of the books against the live balance.
pub(crate) fn check_books(
    booked_msat: u64,
    live: LiveUserBalance,
    prev: &BooksCheck,
    now_ms: i64,
) -> (BooksCheck, Option<Transition>) {
    let tol = BOOKS_TOLERANCE_MSAT;
    let below = booked_msat.saturating_sub(prev.accepted_gap_msat) > live.high_msat.saturating_add(tol);
    let above = !below && live.low_msat > booked_msat.saturating_add(tol);
    let direction = if below {
        Some(GapDirection::Below)
    } else if above {
        Some(GapDirection::Above)
    } else {
        None
    };
    let same = direction.is_some() && prev.direction == direction;
    let first_seen_ms = match direction {
        None => None,
        Some(_) if same => prev.first_seen_ms.or(Some(now_ms)),
        Some(_) => Some(now_ms),
    };
    let sightings = if direction.is_none() { 0 } else if same { prev.sightings.saturating_add(1) } else { 1 };
    let accepted_gap_msat = if live.high_msat.saturating_add(tol) >= booked_msat { 0 } else { prev.accepted_gap_msat };
    // Only a state that wrote a row is cleared with one: a pause, or a surplus that lasted.
    let reported = prev.state == CheckState::Paused
        || prev.direction == Some(GapDirection::Above) && prev.sightings >= BOOKS_SIGHTINGS_TO_PAUSE;

    let (state, gap_msat, transition) = match direction {
        Some(GapDirection::Below) => {
            let paused = prev.state == CheckState::Paused || sightings >= BOOKS_SIGHTINGS_TO_PAUSE;
            let gap = booked_msat.saturating_sub(live.high_msat);
            if paused {
                let t = (prev.state != CheckState::Paused).then_some(Transition::Paused);
                (CheckState::Paused, gap, t)
            } else {
                // A gap below replacing a surplus that lasted clears that report; watching alone writes nothing.
                let t = (prev.state == CheckState::Ok).then_some(if reported { Transition::Cleared } else { Transition::Watching });
                (CheckState::Watching, gap, t)
            }
        }
        Some(GapDirection::Above) => {
            let gap = live.low_msat.saturating_sub(booked_msat);
            let t = if prev.state == CheckState::Paused {
                Some(Transition::Cleared)
            } else if sightings == BOOKS_SIGHTINGS_TO_PAUSE {
                Some(Transition::LastingAbove { excess_msat: gap })
            } else {
                None
            };
            (CheckState::Ok, gap, t)
        }
        None => (CheckState::Ok, 0, reported.then_some(Transition::Cleared)),
    };
    let check = BooksCheck {
        state,
        direction,
        gap_msat,
        first_seen_ms,
        sightings,
        accepted_gap_msat,
        checked_at_ms: Some(now_ms),
    };
    (check, transition)
}

/// Sats by which native from the books may differ from the stored native before the shadow log names it.
pub(crate) const BOOKS_NATIVE_TOLERANCE_SATS: u64 = 5;

/// The books a check compares with the live balance: forwards still waiting in the inbox have already left or reached it.
pub(crate) fn booked_with_queued(booked_msat: u64, queued: ForwardCursor) -> u64 {
    booked_msat.saturating_sub(queued.spent_msat).saturating_add(queued.received_msat)
}

/// `detail` with every field of the object `figures` added.
pub(crate) fn with_figures(mut detail: serde_json::Value, figures: &serde_json::Value) -> serde_json::Value {
    if let (Some(detail), Some(figures)) = (detail.as_object_mut(), figures.as_object()) {
        detail.extend(figures.clone());
    }
    detail
}

/// The ledger row a check's transition writes, with the tick's figures; watching writes none.
pub(crate) fn transition_row(
    transition: Option<Transition>,
    check: &BooksCheck,
    figures: &serde_json::Value,
) -> Option<(&'static str, serde_json::Value)> {
    let row = |detail| with_figures(detail, figures);
    match transition? {
        Transition::Watching => None,
        Transition::Paused => Some((
            "BOOKED_BALANCE_MISMATCH",
            row(serde_json::json!({ "direction": "below", "gap_msat": check.gap_msat, "effect": "log_only" })),
        )),
        Transition::LastingAbove { excess_msat } => Some((
            "BOOKED_BALANCE_MISMATCH",
            row(serde_json::json!({ "direction": "above", "excess_msat": excess_msat, "effect": "log_only" })),
        )),
        Transition::Cleared => Some(("BOOKED_BALANCE_MISMATCH_CLEARED", row(serde_json::json!({})))),
    }
}

/// Native from the books and its distance from the stored native, when that is beyond the tolerance.
pub(crate) fn native_difference(booked_msat: u64, backing_sats: u64, native_sats: u64) -> Option<(u64, u64)> {
    let booked_native_sats = (booked_msat / 1000).saturating_sub(backing_sats);
    let difference_sats = booked_native_sats.abs_diff(native_sats);
    (difference_sats > BOOKS_NATIVE_TOLERANCE_SATS).then_some((booked_native_sats, difference_sats))
}

/// Weight of an anchor channel's commitment transaction without HTLCs.
const COMMITMENT_BASE_WEIGHT: u64 = 1124;
/// Value of each of the commitment transaction's two anchors.
const ANCHOR_SATS: u64 = 330;
/// Sats the allowance adds for rounding and a small HTLC.
const SPLICE_SUSPECT_MARGIN_SATS: u64 = 1_000;

/// Whether the listing leaves out more of the channel than its anchors and held payments, beyond an allowance: LDK holds a side to a negotiated splice-out while the value stays on the old funding.
pub(crate) fn splice_suspected(channel: &Channel, held_msat: u64) -> bool {
    let listed_msat = channel
        .outbound_capacity_msat
        .saturating_add(channel.unspendable_punishment_reserve.unwrap_or(0).saturating_mul(1000))
        .saturating_add(channel.inbound_capacity_msat)
        .saturating_add(channel.counterparty_unspendable_punishment_reserve.saturating_mul(1000))
        .saturating_add(held_msat);
    let shortfall_msat = channel.channel_value_sats.saturating_mul(1000).saturating_sub(listed_msat);
    // LDK lists both sides before the commitment fee, so only the anchors are missing; the fee stays in the allowance so a healthy channel is never taken for a splice, at the cost of a smaller splice-out still being logged.
    let fee_sats = u64::from(channel.feerate_sat_per_1000_weight) * COMMITMENT_BASE_WEIGHT / 1000;
    shortfall_msat > (fee_sats + 2 * ANCHOR_SATS + SPLICE_SUSPECT_MARGIN_SATS) * 1000
}

/// Whether LDK's totals for a channel are behind the cursor on either side, as after its store was restored; a channel with no record counts from zero.
pub(crate) fn totals_behind_cursor(cursor: ForwardCursor, totals: Option<ForwardCursor>) -> bool {
    let totals = totals.unwrap_or_default();
    totals.spent_msat < cursor.spent_msat
        || totals.spent_count < cursor.spent_count
        || totals.received_msat < cursor.received_msat
        || totals.received_count < cursor.received_count
}

/// What else a tick saw on a channel that may explain a disagreement.
#[derive(Debug, Clone, Copy, Default)]
pub(crate) struct DisagreementContext {
    pub top_up_on_the_way: bool,
    pub splice_suspected: bool,
    pub settlement_pending: bool,
    pub events_waiting: bool,
    pub totals_behind_cursor: bool,
}

/// Which side of the live balance the books are on this tick, with what may explain it; empty when they agree and LDK's totals are not behind the books.
pub(crate) fn disagreement_categories(
    check: &BooksCheck,
    channel: &Channel,
    held_msat: u64,
    seen: &DisagreementContext,
) -> Vec<&'static str> {
    let mut found = Vec::new();
    match check.direction {
        Some(GapDirection::Below) => found.push("books_above_live"),
        Some(GapDirection::Above) => found.push("books_below_live"),
        None => {}
    }
    if found.is_empty() {
        // Totals behind the cursor leave missed forwards uncaught, so they are logged even while the books agree.
        if seen.totals_behind_cursor {
            found.push("totals_behind_cursor");
        }
        return found;
    }
    let context = [
        (channel.inbound_capacity_msat == 0, "reserve_unknown"),
        (!channel.is_outbound, "user_funded"),
        (held_msat > 0, "held_payment"),
        (seen.top_up_on_the_way, "top_up_on_the_way"),
        (seen.splice_suspected, "splice_suspected"),
        (seen.settlement_pending, "settlement_pending"),
        (seen.events_waiting, "events_waiting"),
        (seen.totals_behind_cursor, "totals_behind_cursor"),
    ];
    found.extend(context.into_iter().filter_map(|(applies, name)| applies.then_some(name)));
    found.sort_unstable();
    found
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::stable_manager::tests::FakeLdkServer;
    use ldk_server_client::ldk_server_grpc::types::ChannelForwardingStats;
    use std::sync::atomic::Ordering;

    fn stats(id: &str, n: u64) -> ChannelForwardingStats {
        ChannelForwardingStats {
            channel_id: id.to_string(),
            inbound_payments_forwarded: n,
            outbound_payments_forwarded: n + 1,
            total_inbound_amount_msat: n * 1_000 + 7,
            total_outbound_amount_msat: n * 2_000 + 13,
            ..Default::default()
        }
    }

    #[tokio::test]
    async fn forwarding_totals_reads_every_page() {
        let fake = FakeLdkServer::new(vec![])
            .with_forwarding_stats(vec![stats("aa", 1), stats("bb", 2), stats("cc", 3)])
            .with_stats_page_size(1);
        let totals = forwarding_totals(&fake).await.unwrap();
        assert_eq!(totals.len(), 3);
        let c = &totals["bb"];
        assert_eq!(c.spent_msat, 2_007);
        assert_eq!(c.spent_count, 2);
        assert_eq!(c.received_msat, 4_013);
        assert_eq!(c.received_count, 3);
    }

    #[tokio::test]
    async fn forwarding_totals_stops_at_a_repeated_page_token() {
        let fake = FakeLdkServer::new(vec![]);
        *fake.stats_next_token.lock().unwrap() = Some(|_| "0".to_owned());
        assert!(forwarding_totals(&fake).await.is_err());
        assert_eq!(fake.stats_calls.load(Ordering::SeqCst), 2, "the second page hands back the token it was asked with");
    }

    #[tokio::test]
    async fn forwarding_totals_stops_after_a_thousand_pages() {
        let fake = FakeLdkServer::new(vec![]);
        *fake.stats_next_token.lock().unwrap() = Some(|asked| (asked + 1).to_string());
        assert!(forwarding_totals(&fake).await.is_err());
        assert_eq!(fake.stats_calls.load(Ordering::SeqCst), 1_000);
    }

    #[tokio::test]
    async fn forwarding_totals_passes_the_error_on() {
        let fake = FakeLdkServer::new(vec![]).with_forwarding_stats(vec![stats("aa", 1)]);
        fake.stats_fail.store(true, Ordering::SeqCst);
        assert!(forwarding_totals(&fake).await.is_err());
    }

    use crate::stable_manager::tests::make_channel;

    fn cur(spent: u64, spent_n: u64, received: u64, received_n: u64) -> ForwardCursor {
        ForwardCursor { spent_msat: spent, received_msat: received, spent_count: spent_n, received_count: received_n }
    }

    fn live(low: u64, high: u64) -> LiveUserBalance {
        LiveUserBalance { low_msat: low, high_msat: high }
    }

    #[test]
    fn a_counted_forward_books_its_amount_and_moves_the_cursor() {
        let r = book_forward_event(cur(0, 0, 0, 0), Some(cur(10_500, 1, 0, 0)), ForwardSide::OutOfUser, 10_500, true);
        assert_eq!(r, (10_500, cur(10_500, 1, 0, 0)));
    }

    #[test]
    fn a_counted_forward_the_totals_already_cover_books_nothing() {
        let c = cur(10_500, 1, 0, 0);
        let r = book_forward_event(c, Some(c), ForwardSide::OutOfUser, 10_500, true);
        assert_eq!(r, (0, c));
    }

    #[test]
    fn a_forward_ldk_does_not_count_leaves_the_cursor() {
        let c = cur(1, 1, 2, 2);
        let r = book_forward_event(c, Some(cur(9_000, 3, 9_000, 3)), ForwardSide::IntoUser, 7_000, false);
        assert_eq!(r, (7_000, c));
    }

    #[test]
    fn without_totals_a_counted_forward_books_its_amount() {
        let r = book_forward_event(cur(0, 0, 100, 1), None, ForwardSide::IntoUser, 7_000, true);
        assert_eq!(r, (7_000, cur(0, 0, 7_100, 2)));
    }

    #[test]
    fn catch_up_nets_a_spend_and_a_receive() {
        let totals = cur(10_000, 1, 15_000, 1);
        let c = catch_up(ForwardCursor::default(), totals, ForwardCursor::default()).unwrap();
        assert_eq!((c.spent_msat, c.received_msat, c.net_msat), (10_000, 15_000, 5_000));
        assert_eq!(c.cursor, totals);
    }

    #[test]
    fn catch_up_leaves_queued_forwards() {
        let c = catch_up(ForwardCursor::default(), cur(10_000, 2, 0, 0), cur(4_000, 1, 0, 0)).unwrap();
        assert_eq!(c.spent_msat, 6_000);
        assert_eq!(c.cursor, cur(6_000, 1, 0, 0));
    }

    #[test]
    fn catch_up_never_moves_the_cursor_back() {
        assert!(catch_up(cur(6_000, 1, 0, 0), cur(10_000, 2, 0, 0), cur(5_000, 1, 0, 0)).is_none());
    }

    #[test]
    fn below_the_reserve_the_balance_is_a_range() {
        let mut ch = make_channel("aa", "1", "02", 100_000, 100_000_000, true);
        ch.inbound_capacity_msat = 0;
        ch.counterparty_unspendable_punishment_reserve = 10_000;
        let l = live_user_balance(&ch, 0);
        assert_eq!((l.low_msat, l.high_msat), (0, 10_000_000));
    }

    #[test]
    fn a_gap_below_is_watched_then_paused() {
        let l = live(40_000_000, 40_000_000);
        let (c1, t1) = check_books(50_000_000, l, &BooksCheck::default(), 1_000);
        assert_eq!((c1.state, t1), (CheckState::Watching, Some(Transition::Watching)));
        assert_eq!((c1.direction, c1.gap_msat, c1.sightings), (Some(GapDirection::Below), 10_000_000, 1));
        let (c2, t2) = check_books(50_000_000, l, &c1, 2_000);
        assert_eq!((c2.state, t2), (CheckState::Paused, Some(Transition::Paused)));
        assert_eq!(c2.first_seen_ms, Some(1_000));
        let (c3, t3) = check_books(50_000_000, l, &c2, 3_000);
        assert_eq!((c3.state, t3, c3.sightings), (CheckState::Paused, None, 3));
    }

    #[test]
    fn a_gap_inside_the_tolerance_is_ok() {
        let (c, t) = check_books(40_005_000, live(40_000_000, 40_000_000), &BooksCheck::default(), 1);
        assert_eq!((c.state, c.direction, t), (CheckState::Ok, None, None));
    }

    #[test]
    fn a_lasting_surplus_is_reported_once() {
        let l = live(70_000_000, 70_000_000);
        let (c1, t1) = check_books(50_000_000, l, &BooksCheck::default(), 1);
        assert_eq!((c1.state, c1.direction, t1), (CheckState::Ok, Some(GapDirection::Above), None));
        let (c2, t2) = check_books(50_000_000, l, &c1, 2);
        assert_eq!(t2, Some(Transition::LastingAbove { excess_msat: 20_000_000 }));
        let (_, t3) = check_books(50_000_000, l, &c2, 3);
        assert_eq!(t3, None);
    }

    #[test]
    fn the_gap_closing_clears_the_state() {
        let prev = BooksCheck {
            state: CheckState::Paused,
            direction: Some(GapDirection::Below),
            gap_msat: 10_000_000,
            first_seen_ms: Some(1),
            sightings: 2,
            ..Default::default()
        };
        let (c, t) = check_books(50_000_000, live(50_000_000, 50_000_000), &prev, 9);
        assert_eq!((c.state, c.direction, t), (CheckState::Ok, None, Some(Transition::Cleared)));
    }

    #[test]
    fn a_surplus_after_a_pause_clears_it() {
        let prev = BooksCheck {
            state: CheckState::Paused,
            direction: Some(GapDirection::Below),
            gap_msat: 10_000_000,
            first_seen_ms: Some(1),
            sightings: 2,
            ..Default::default()
        };
        let (c, t) = check_books(50_000_000, live(70_000_000, 70_000_000), &prev, 9);
        assert_eq!((c.state, c.direction, c.sightings, t), (CheckState::Ok, Some(GapDirection::Above), 1, Some(Transition::Cleared)));
        assert_eq!(c.first_seen_ms, Some(9));
    }

    #[test]
    fn an_accepted_gap_is_not_reported_until_it_grows() {
        let prev = BooksCheck { accepted_gap_msat: 10_000_000, ..Default::default() };
        let (c, t) = check_books(50_000_000, live(40_000_000, 40_000_000), &prev, 1);
        assert_eq!((c.state, t), (CheckState::Ok, None));
        assert_eq!(c.accepted_gap_msat, 10_000_000);
        let (c, t) = check_books(52_000_000, live(40_000_000, 40_000_000), &prev, 1);
        assert_eq!((c.state, t), (CheckState::Watching, Some(Transition::Watching)));
    }

    #[test]
    fn a_gap_that_was_never_reported_clears_quietly() {
        let agree = live(50_000_000, 50_000_000);
        // One sighting below, then back within the tolerance.
        let (watching, _) = check_books(50_000_000, live(40_000_000, 40_000_000), &BooksCheck::default(), 1);
        let (c, t) = check_books(50_000_000, agree, &watching, 2);
        assert_eq!((c.state, c.direction, t), (CheckState::Ok, None, None));
        // One sighting above, then back.
        let (above, _) = check_books(50_000_000, live(70_000_000, 70_000_000), &BooksCheck::default(), 1);
        let (c, t) = check_books(50_000_000, agree, &above, 2);
        assert_eq!((c.state, c.direction, t), (CheckState::Ok, None, None));
    }

    #[test]
    fn a_pause_that_turns_into_a_surplus_is_cleared_once() {
        let paused = BooksCheck {
            state: CheckState::Paused,
            direction: Some(GapDirection::Below),
            gap_msat: 10_000_000,
            first_seen_ms: Some(1),
            sightings: 2,
            ..Default::default()
        };
        let (above, t1) = check_books(50_000_000, live(70_000_000, 70_000_000), &paused, 2);
        let (_, t2) = check_books(50_000_000, live(50_000_000, 50_000_000), &above, 3);
        assert_eq!((t1, t2), (Some(Transition::Cleared), None));
    }

    #[test]
    fn a_reported_surplus_is_cleared_when_it_goes() {
        let l = live(70_000_000, 70_000_000);
        let (c1, _) = check_books(50_000_000, l, &BooksCheck::default(), 1);
        let (c2, t2) = check_books(50_000_000, l, &c1, 2);
        assert_eq!(t2, Some(Transition::LastingAbove { excess_msat: 20_000_000 }));
        let (_, t3) = check_books(50_000_000, live(50_000_000, 50_000_000), &c2, 3);
        assert_eq!(t3, Some(Transition::Cleared));
        // A gap below in its place clears the report and is only watched.
        let (c4, t4) = check_books(50_000_000, live(40_000_000, 40_000_000), &c2, 3);
        assert_eq!((c4.state, t4), (CheckState::Watching, Some(Transition::Cleared)));
    }

    #[test]
    fn a_stored_pause_whose_direction_is_unreadable_stays_paused() {
        let paused = BooksCheck { state: CheckState::Paused, direction: None, gap_msat: 10_000_000, first_seen_ms: Some(1), sightings: 2, ..Default::default() };
        let (c, t) = check_books(50_000_000, live(40_000_000, 40_000_000), &paused, 2);
        assert_eq!((c.state, c.direction, t), (CheckState::Paused, Some(GapDirection::Below), None));
    }

    #[test]
    fn categories_name_a_waiting_pay_back_waiting_events_and_totals_behind_the_books() {
        use crate::stable_manager::tests::{CHANNEL_ID_HEX, COUNTERPARTY_HEX, USER_CHANNEL_ID_DECIMAL};
        let ch = make_channel(CHANNEL_ID_HEX, USER_CHANNEL_ID_DECIMAL, COUNTERPARTY_HEX, 100_000, 50_000_000, true);
        let below = BooksCheck { direction: Some(GapDirection::Below), ..Default::default() };
        let seen = DisagreementContext { settlement_pending: true, events_waiting: true, totals_behind_cursor: true, ..Default::default() };
        assert_eq!(disagreement_categories(&below, &ch, 0, &seen), ["books_above_live", "events_waiting", "settlement_pending", "totals_behind_cursor"]);
        // Totals behind the books are named on their own while the books agree, and nothing else is.
        assert_eq!(disagreement_categories(&BooksCheck::default(), &ch, 0, &seen), ["totals_behind_cursor"]);
        let quiet = DisagreementContext { totals_behind_cursor: false, ..seen };
        assert!(disagreement_categories(&BooksCheck::default(), &ch, 0, &quiet).is_empty());
    }

    #[test]
    fn totals_behind_the_cursor_on_either_side_are_noticed() {
        let cursor = cur(7_350_000, 3, 4_125_500, 2);
        assert!(!totals_behind_cursor(cursor, Some(cursor)));
        assert!(!totals_behind_cursor(cursor, Some(cur(7_360_500, 4, 4_125_500, 2))));
        assert!(totals_behind_cursor(cursor, Some(cur(7_350_000, 3, 4_000_000, 2))));
        assert!(totals_behind_cursor(cursor, Some(cur(7_350_000, 2, 4_125_500, 2))));
        assert!(totals_behind_cursor(cursor, None), "a restored store may hold no record for the channel");
        assert!(!totals_behind_cursor(ForwardCursor::default(), None));
    }

    #[test]
    fn categories_name_the_side_of_the_books_and_what_may_explain_it() {
        use crate::stable_manager::tests::{CHANNEL_ID_HEX, COUNTERPARTY_HEX, USER_CHANNEL_ID_DECIMAL};
        let mut ch = make_channel(CHANNEL_ID_HEX, USER_CHANNEL_ID_DECIMAL, COUNTERPARTY_HEX, 100_000, 50_000_000, true);
        ch.is_outbound = false;
        let below = BooksCheck { direction: Some(GapDirection::Below), ..Default::default() };
        let above = BooksCheck { direction: Some(GapDirection::Above), ..Default::default() };
        let agree = BooksCheck::default();
        let none = DisagreementContext::default();
        assert_eq!(disagreement_categories(&below, &ch, 0, &none), ["books_above_live", "user_funded"]);
        let top_up = DisagreementContext { top_up_on_the_way: true, ..none };
        assert_eq!(disagreement_categories(&above, &ch, 7_000, &top_up), ["books_below_live", "held_payment", "top_up_on_the_way", "user_funded"]);
        let all_but_totals = DisagreementContext { top_up_on_the_way: true, splice_suspected: true, settlement_pending: true, events_waiting: true, totals_behind_cursor: false };
        assert!(disagreement_categories(&agree, &ch, 7_000, &all_but_totals).is_empty(), "context alone is no disagreement");
        // Native is compared on its own line: books of 50,000 sats on 40,000 of backing hold 10,000 native.
        assert_eq!(native_difference(50_000_000, 40_000, 9_994), Some((10_000, 6)));
        assert_eq!(native_difference(50_000_000, 40_000, 10_005), None, "within the tolerance");
        assert_eq!(native_difference(30_000_000, 40_000, 3), None, "books below backing hold no native");
        ch.inbound_capacity_msat = 0;
        ch.is_outbound = true;
        assert_eq!(disagreement_categories(&below, &ch, 0, &none), ["books_above_live", "reserve_unknown"]);
        ch.inbound_capacity_msat = 30_000_000;
        assert_eq!(disagreement_categories(&above, &ch, 0, &DisagreementContext { splice_suspected: true, ..none }), ["books_below_live", "splice_suspected"]);
    }

    #[test]
    fn a_listing_short_of_its_value_is_read_as_a_splice_out_confirming() {
        use crate::stable_manager::tests::{CHANNEL_ID_HEX, COUNTERPARTY_HEX, USER_CHANNEL_ID_DECIMAL};
        // A 100,000-sat anchor channel the LSP funded: LDK lists both sides before the commitment fee, so only the two 330-sat anchors are missing from the LSP's side.
        let mut ch = make_channel(CHANNEL_ID_HEX, USER_CHANNEL_ID_DECIMAL, COUNTERPARTY_HEX, 100_000, 0, true);
        ch.feerate_sat_per_1000_weight = 2_500;
        ch.unspendable_punishment_reserve = Some(1_000);
        ch.counterparty_unspendable_punishment_reserve = 1_000;
        ch.outbound_capacity_msat = (100_000 - 50_000 - 660 - 1_000) * 1000;
        ch.inbound_capacity_msat = (50_000 - 1_000) * 1000;
        assert!(!splice_suspected(&ch, 0), "both sides, their reserves and the anchors make up the value");
        // A payment from the user the node still holds has left their side but is in neither.
        ch.inbound_capacity_msat -= 5_000_000;
        assert!(splice_suspected(&ch, 0));
        assert!(!splice_suspected(&ch, 5_000_000));
        // The allowance keeps a 2,810-sat commitment fee at 2,500 sat/kw, so a 3,000-sat splice-out is not told apart.
        ch.inbound_capacity_msat = (50_000 - 3_000 - 1_000) * 1000;
        assert!(!splice_suspected(&ch, 0));
        // A negotiated 20,000-sat splice-out leaves the user's side while the value stays on the old funding.
        ch.inbound_capacity_msat = (30_000 - 1_000) * 1000;
        assert!(splice_suspected(&ch, 0));
    }

    #[test]
    fn a_pause_and_a_lasting_surplus_are_logged_only() {
        let figures = serde_json::json!({ "booked_msat": 23_000_000, "live_low_msat": 30_000_000 });
        let check = BooksCheck { state: CheckState::Paused, direction: Some(GapDirection::Below), gap_msat: 20_000_000, ..Default::default() };
        let (name, detail) = transition_row(Some(Transition::Paused), &check, &figures).unwrap();
        assert_eq!((name, &detail["direction"], &detail["gap_msat"], &detail["effect"]), ("BOOKED_BALANCE_MISMATCH", &"below".into(), &20_000_000.into(), &"log_only".into()));
        let (name, detail) = transition_row(Some(Transition::LastingAbove { excess_msat: 7_000_000 }), &check, &figures).unwrap();
        assert_eq!((name, &detail["direction"], &detail["excess_msat"], &detail["booked_msat"]), ("BOOKED_BALANCE_MISMATCH", &"above".into(), &7_000_000.into(), &23_000_000.into()));
        assert_eq!(detail["live_low_msat"], 30_000_000);
        assert_eq!(transition_row(Some(Transition::Cleared), &check, &figures).unwrap().0, "BOOKED_BALANCE_MISMATCH_CLEARED");
        assert!(transition_row(Some(Transition::Watching), &check, &figures).is_none());
        assert!(transition_row(None, &check, &figures).is_none());
    }

    #[test]
    fn the_check_counts_forwards_waiting_in_the_inbox() {
        assert_eq!(booked_with_queued(50_000_000, cur(2_000_500, 1, 700_000, 1)), 48_699_500);
        assert_eq!(booked_with_queued(1_000, cur(2_000_500, 1, 0, 0)), 0, "never below zero");
        assert_eq!(booked_with_queued(50_000_000, ForwardCursor::default()), 50_000_000);
    }
}
