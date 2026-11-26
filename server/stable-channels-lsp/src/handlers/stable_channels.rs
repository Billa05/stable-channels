//! ListStableChannels handler. Reads from the in-memory StableChannelManager.

use axum::body::Bytes;
use axum::extract::State;
use axum::response::Response;

use sc_protos::stable::{
    EditStableChannelRequest, EditStableChannelResponse, ListSettlementPaymentsRequest,
    ListSettlementPaymentsResponse, ListStableChannelsRequest, ListStableChannelsResponse,
    ReleaseStabilityPaymentRequest, ReleaseStabilityPaymentResponse, ResolveBooksGapRequest,
    ResolveBooksGapResponse, SettlementPayment, StableChannelInfo,
};
use stable_channels::db::{BookedBalance, GapChoice};
use stable_channels::price_feeds::get_fresh_cached_price_no_fetch;

use crate::handlers::{decode_body, error_response, ok_response};
use crate::stable_manager::{EditOutcome, ReleaseOutcome, ResolveOutcome};
use crate::state::AppState;

fn to_proto_locations(rows: Vec<stable_channels::db::PeerLocationRecord>) -> Vec<sc_protos::stable::PeerLocation> {
    rows.into_iter()
        .map(|row| sc_protos::stable::PeerLocation {
            ip: row.ip,
            country_code: row.country_code.unwrap_or_default(),
            country_name: row.country_name.unwrap_or_default(),
            first_seen_at: row.first_seen_at,
            last_seen_at: row.last_seen_at,
        })
        .collect()
}

/// A stable user's latest sightings; none while recording is off, whatever an earlier run stored.
fn recent_locations(db: &stable_channels::db::Database, record_ips: bool, node_id: &str) -> Vec<sc_protos::stable::PeerLocation> {
    if !record_ips {
        return Vec::new();
    }
    to_proto_locations(db.recent_peer_locations(node_id, 10).unwrap_or_default())
}

/// Fills a channel's books fields from its open books; a channel without books leaves them empty.
fn add_books(info: &mut StableChannelInfo, books: Option<&BookedBalance>) {
    let Some(books) = books else { return };
    let check = &books.check;
    info.booked_sats = Some(books.booked_user_msat / 1000);
    info.books_state = Some(check.state.as_str().to_owned());
    info.books_gap_sats = Some(check.gap_msat / 1000);
    info.books_gap_since_ms = check.first_seen_ms;
    info.books_accepted_gap_sats = Some(check.accepted_gap_msat.saturating_add(check.accepted_backing_gap_msat) / 1000);
    info.books_gap_kind = check.gap_kind.map(|kind| kind.as_str().to_owned());
}

pub async fn list_stable_channels(
    State(state): State<AppState>,
    body: Bytes,
) -> Response {
    if let Err(resp) = decode_body::<ListStableChannelsRequest>(&body) {
        return resp;
    }

    let latest_price = get_fresh_cached_price_no_fetch();
    // One read for every row; a missing entry reports 0 so old readers see nothing new.
    let created_at = state.db.channel_created_at().unwrap_or_default();
    // One read for every row's books; books that cannot be read are left out, and the tick reports the failure.
    let books: std::collections::HashMap<u128, BookedBalance> =
        state.db.booked_balances().unwrap_or_default().into_iter().map(|books| (books.user_channel_id, books)).collect();

    let mgr = state.stable_manager.lock().await;
    let channels = mgr
        .stable_channels
        .iter()
        .map(|sc| {
            let mut info = StableChannelInfo {
                channel_id: sc.channel_id.to_string(),
                counterparty: sc.counterparty.to_string(),
                expected_usd: sc.expected_usd.0,
                expected_msats: sc.backing_sats.saturating_mul(1_000),
                latest_price,
                note: sc.note.clone().unwrap_or_default(),
                is_stable_receiver: sc.is_stable_receiver,
                user_channel_id: format!("{}", sc.user_channel_id),
                recent_locations: recent_locations(&state.db, state.geoip_record_ips, &sc.counterparty.to_string()),
                created_at: created_at.get(&sc.user_channel_id.to_string()).copied().unwrap_or(0),
                ..Default::default()
            };
            add_books(&mut info, books.get(&sc.user_channel_id));
            info
        })
        .collect::<Vec<_>>();
    drop(mgr);

    ok_response(ListStableChannelsResponse { channels })
}

pub async fn edit_stable_channel(
    State(state): State<AppState>,
    body: Bytes,
) -> Response {
    let req: EditStableChannelRequest = match decode_body(&body) {
        Ok(r) => r,
        Err(resp) => return resp,
    };

    let btc_price = get_fresh_cached_price_no_fetch();

    let EditOutcome { ok, status } = {
        let mut mgr = state.stable_manager.lock().await;
        mgr.edit_stable_channel(
            &req.channel_id,
            req.expected_usd,
            req.note,
            state.ldk_server.as_ref() as &dyn crate::stable_manager::LdkServerCalls,
            btc_price,
        )
        .await
    };

    ok_response(EditStableChannelResponse { ok, status })
}

/// Whether a release counts the payment as arrived; None for anything but the two spelled-out answers, so an empty request can never drop a payment.
fn release_decision(decision: &str) -> Option<bool> {
    match decision {
        "arrived" => Some(true),
        "not_arrived" => Some(false),
        _ => None,
    }
}

/// Operator's decision about a stability payment the node will not report on.
pub async fn release_stability_payment(
    State(state): State<AppState>,
    body: Bytes,
) -> Response {
    let req: ReleaseStabilityPaymentRequest = match decode_body(&body) {
        Ok(r) => r,
        Err(resp) => return resp,
    };

    let Some(arrived) = release_decision(&req.decision) else {
        let status = format!("Unknown decision {:?}; send \"arrived\" or \"not_arrived\"", req.decision);
        return ok_response(ReleaseStabilityPaymentResponse { ok: false, status, needs_acknowledgement: false });
    };
    let btc_price = get_fresh_cached_price_no_fetch();

    let ReleaseOutcome { ok, status, needs_acknowledgement } = {
        let mut mgr = state.stable_manager.lock().await;
        mgr.release_stability_payment(
            &req.payment_id,
            arrived,
            req.acknowledged,
            state.ldk_server.as_ref() as &dyn crate::stable_manager::LdkServerCalls,
            btc_price,
        )
        .await
    };

    ok_response(ReleaseStabilityPaymentResponse { ok, status, needs_acknowledgement })
}

/// The resolution a request names; None for anything but the three numbered choices, so an empty request can never change the books.
fn gap_choice(choice: i32) -> Option<GapChoice> {
    match choice {
        1 => Some(GapChoice::Spend),
        2 => Some(GapChoice::PaymentToLsp),
        3 => Some(GapChoice::Keep),
        _ => None,
    }
}

/// Operator's resolution of a paused channel's books gap.
pub async fn resolve_books_gap(
    State(state): State<AppState>,
    body: Bytes,
) -> Response {
    let req: ResolveBooksGapRequest = match decode_body(&body) {
        Ok(r) => r,
        Err(resp) => return resp,
    };

    let Some(choice) = gap_choice(req.choice) else {
        let status = format!("Unknown choice {}; send 1 (spend), 2 (payment to the LSP) or 3 (keep the books)", req.choice);
        return ok_response(ResolveBooksGapResponse { ok: false, status, ..Default::default() });
    };
    let btc_price = get_fresh_cached_price_no_fetch();

    let ResolveOutcome { ok, status, needs_acknowledgement, booked_sats, actual_sats, gap_sats, new_expected_usd, new_backing_sats } = {
        let mut mgr = state.stable_manager.lock().await;
        mgr.resolve_books_gap(
            &req.channel_id,
            choice,
            req.acknowledged,
            req.expected_actual_sats,
            req.expected_gap_sats,
            req.expected_new_expected_usd,
            req.expected_new_backing_sats,
            req.reason,
            state.ldk_server.as_ref() as &dyn crate::stable_manager::LdkServerCalls,
            btc_price,
        )
        .await
    };

    ok_response(ResolveBooksGapResponse { ok, status, needs_acknowledgement, booked_sats, actual_sats, gap_sats, new_expected_usd, new_backing_sats })
}

pub async fn list_settlement_payments(
    State(state): State<AppState>,
    body: Bytes,
) -> Response {
    if let Err(resp) = decode_body::<ListSettlementPaymentsRequest>(&body) {
        return resp;
    }

    let rows = match state.db.list_settlements() {
        Ok(r) => r,
        Err(e) => {
            return error_response(
                ldk_server_client::ldk_server_grpc::error::ErrorCode::InternalServerError,
                format!("list_settlements failed: {}", e),
            )
        }
    };

    let settlements = rows
        .into_iter()
        .map(|(payment_id, kind)| SettlementPayment { payment_id, kind })
        .collect::<Vec<_>>();

    ok_response(ListSettlementPaymentsResponse { settlements })
}

#[cfg(test)]
mod tests {
    use super::*;
    use prost::Message;
    use stable_channels::db::PeerLocationRecord;

    #[test]
    fn release_needs_a_spelled_out_decision() {
        assert_eq!(release_decision("arrived"), Some(true));
        assert_eq!(release_decision("not_arrived"), Some(false));
        for unclear in ["", "Arrived", "true", "false", "not arrived"] {
            assert_eq!(release_decision(unclear), None, "{unclear:?}");
        }
    }

    #[test]
    fn a_resolution_needs_a_known_choice() {
        assert_eq!(gap_choice(1), Some(GapChoice::Spend));
        assert_eq!(gap_choice(2), Some(GapChoice::PaymentToLsp));
        assert_eq!(gap_choice(3), Some(GapChoice::Keep));
        for unclear in [0, 4, -1] {
            assert_eq!(gap_choice(unclear), None, "{unclear}");
        }
    }

    #[test]
    fn the_list_reports_each_channels_books() {
        use stable_channels::db::{BookedBalance, BooksCheck, CheckState, ForwardCursor, GapDirection, GapKind};
        let mut info = StableChannelInfo { channel_id: "c".into(), ..Default::default() };
        add_books(&mut info, None);
        assert_eq!((info.booked_sats, info.books_state.as_deref(), info.books_gap_sats), (None, None, None), "no books yet");
        assert_eq!(info.books_gap_kind, None);
        let books = BookedBalance {
            user_channel_id: 42,
            channel_id: "c".into(),
            booked_user_msat: 50_000_700,
            channel_value_sats: 100_000,
            cursor: ForwardCursor::default(),
            check: BooksCheck {
                state: CheckState::Paused,
                direction: Some(GapDirection::Below),
                gap_msat: 20_000_400,
                first_seen_ms: Some(1_764_200_000_000),
                sightings: 2,
                accepted_gap_msat: 3_000_000,
                accepted_backing_gap_msat: 2_000_000,
                checked_at_ms: Some(1_764_200_060_000),
                gap_kind: Some(GapKind::Backing),
            },
            closed_at: None,
            uncut: None,
            accepted_shortfall_msat: 0,
        };
        add_books(&mut info, Some(&books));
        assert_eq!((info.booked_sats, info.books_state.as_deref(), info.books_gap_sats), (Some(50_000), Some("paused"), Some(20_000)));
        assert_eq!((info.books_gap_since_ms, info.books_accepted_gap_sats), (Some(1_764_200_000_000), Some(5_000)));
        assert_eq!(info.books_gap_kind.as_deref(), Some("backing"));
        let mut balance = books.clone();
        balance.check.gap_kind = Some(GapKind::Balance);
        add_books(&mut info, Some(&balance));
        assert_eq!(info.books_gap_kind.as_deref(), Some("balance"));
        // A check written before the side was recorded names none.
        balance.check.gap_kind = None;
        add_books(&mut info, Some(&balance));
        assert_eq!(info.books_gap_kind, None);
    }

    #[test]
    fn locations_map_to_proto_and_old_readers_ignore_them() {
        let rows = vec![PeerLocationRecord {
            ip: "1.2.3.4".into(),
            country_code: Some("IN".into()),
            country_name: Some("India".into()),
            first_seen_at: 10,
            last_seen_at: 20,
        }];
        let info = StableChannelInfo { channel_id: "c".into(), recent_locations: to_proto_locations(rows), ..Default::default() };
        assert_eq!(info.recent_locations[0].country_code, "IN");
        assert_eq!(info.recent_locations[0].last_seen_at, 20);
        // An old reader's struct is the same message without field 9: decoding must still succeed.
        #[derive(Clone, PartialEq, prost::Message)]
        struct OldInfo {
            #[prost(string, tag = "1")]
            channel_id: String,
        }
        assert_eq!(OldInfo::decode(info.encode_to_vec().as_slice()).unwrap().channel_id, "c");
    }

    #[test]
    fn no_locations_are_served_while_recording_is_off() {
        let dir = tempfile::tempdir().unwrap();
        let db = stable_channels::db::Database::open(dir.path()).unwrap();
        db.record_peer_sighting("wallet", "1.2.3.4", None, 10).unwrap();
        assert_eq!(recent_locations(&db, true, "wallet").len(), 1);
        assert!(recent_locations(&db, false, "wallet").is_empty());
    }
}
