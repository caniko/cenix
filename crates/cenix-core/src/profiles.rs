//! Pure profile discovery policy.
//!
//! Android owns live discovery (serials, user types, quiet/running/unlocked
//! flags). Every decision derived from those observations — kind, access,
//! quarantine of provisionally classified profiles — lives here so it is
//! deterministic and unit-tested in Rust. Kotlin only queries Android and
//! forwards the observations across UniFFI.

use super::{ProfileAccess, ProfileDescriptor, ProfileKind};

/// What Android reported for a profile's user type.
/// `None` (lookup failure) is distinct from a successful lookup that returned
/// an unrecognized string: only the former may reuse a previous kind.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UserTypeHint {
    Managed,
    Private,
    Other,
}

/// One observed profile for a discovery refresh.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DiscoveryInput {
    pub profile_id: u64,
    pub owner: bool,
    pub user_type: Option<UserTypeHint>,
}

/// Quarantine bookkeeping. `authoritative` holds serials classified from a
/// successful lookup; `uncertain` holds serials that must stay hidden until
/// discovery succeeds. Both are sorted for deterministic output.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct DiscoveryState {
    pub authoritative: Vec<u64>,
    pub uncertain: Vec<u64>,
}

/// Fold one refresh of observations into the quarantine state.
///
/// A profile becomes authoritative on any successful type lookup (or by being
/// the owner). A failed lookup keeps a never-classified profile uncertain;
/// previously authoritative profiles stay authoritative. Serials that vanish
/// from discovery are dropped from both sets.
pub fn update_discovery(
    state: &DiscoveryState,
    inputs: &[DiscoveryInput],
    existing_ids: &[u64],
) -> DiscoveryState {
    use std::collections::BTreeSet;
    let mut authoritative: BTreeSet<u64> = state.authoritative.iter().copied().collect();
    let mut uncertain = BTreeSet::new();
    for input in inputs {
        if input.user_type.is_some() || input.owner {
            authoritative.insert(input.profile_id);
        } else if !authoritative.contains(&input.profile_id) {
            uncertain.insert(input.profile_id);
        }
    }
    let existing: BTreeSet<u64> = existing_ids.iter().copied().collect();
    for id in state
        .uncertain
        .iter()
        .filter(|id| existing.contains(id) && !authoritative.contains(id))
    {
        uncertain.insert(*id);
    }
    authoritative.retain(|id| existing.contains(id));
    uncertain.retain(|id| !authoritative.contains(id));
    DiscoveryState {
        authoritative: authoritative.into_iter().collect(),
        uncertain: uncertain.into_iter().collect(),
    }
}

/// The previously published kind, but only when it came from an authoritative
/// classification. A failed user-type lookup must not reclassify a known
/// profile, so transient API failures cannot purge its customization.
pub fn previous_kind(
    state: &DiscoveryState,
    current_kinds: &[(u64, ProfileKind)],
    profile_id: u64,
) -> Option<ProfileKind> {
    if !state.authoritative.contains(&profile_id) {
        return None;
    }
    current_kinds
        .iter()
        .find(|(id, _)| *id == profile_id)
        .map(|(_, kind)| *kind)
}

/// One profile observed by Android for a discovery refresh.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ProfileObservation {
    pub profile_id: u64,
    pub owner: bool,
    pub user_type: Option<UserTypeHint>,
    pub quiet: bool,
    pub running: bool,
    pub unlocked: bool,
    /// False when the serial lookup failed and `profile_id` is the last known
    /// serial for this handle: the profile must be hidden and preserved, never
    /// treated as removed or reclassified from a partial observation.
    pub serial_resolved: bool,
}

/// The complete outcome of one refresh: descriptors to publish, the next
/// quarantine state, and serials eligible for destructive cleanup (absent from
/// every observation, including failed lookups).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReconciledProfiles {
    pub descriptors: Vec<ProfileDescriptor>,
    pub state: DiscoveryState,
    pub removed: Vec<u64>,
}

/// Fold one full refresh in a single call so no caller can derive removal or
/// availability from a failed observation. Unresolved observations keep their
/// previous kind (or `Other` when never classified) with fail-closed access,
/// stay out of the authoritative set, and still count as present so their data
/// is preserved. Only serials absent from every observation are removed.
pub fn reconcile_profiles(
    state: &DiscoveryState,
    observations: &[ProfileObservation],
    previous_kinds: &[(u64, ProfileKind)],
) -> ReconciledProfiles {
    let inputs: Vec<DiscoveryInput> = observations
        .iter()
        .filter(|observation| observation.serial_resolved)
        .map(|observation| DiscoveryInput {
            profile_id: observation.profile_id,
            owner: observation.owner,
            user_type: observation.user_type,
        })
        .collect();
    let existing: Vec<u64> = observations
        .iter()
        .map(|observation| observation.profile_id)
        .collect();
    let mut next = update_discovery(state, &inputs, &existing);
    // Unresolved observations never become authoritative, but they still count
    // as provisionally classified: quarantine them so their rows are preserved
    // until discovery succeeds.
    {
        use std::collections::BTreeSet;
        let mut uncertain: BTreeSet<u64> = next.uncertain.iter().copied().collect();
        for observation in observations.iter().filter(|o| !o.serial_resolved) {
            if !next.authoritative.contains(&observation.profile_id) {
                uncertain.insert(observation.profile_id);
            }
        }
        next.uncertain = uncertain.into_iter().collect();
    }
    let mut descriptors = Vec::with_capacity(observations.len());
    for observation in observations {
        let previous = previous_kind(&next, previous_kinds, observation.profile_id);
        // An unresolved serial must not reclassify from a partial observation.
        let user_type = if observation.serial_resolved {
            observation.user_type
        } else {
            None
        };
        let mut descriptor = classify_profile(
            observation.profile_id,
            observation.owner,
            user_type,
            observation.quiet,
            observation.running,
            observation.unlocked,
            previous,
        );
        // Flags observed alongside a failed lookup are not trustworthy: an
        // unresolved profile must never publish availability.
        if !observation.serial_resolved && descriptor.access == ProfileAccess::Available {
            descriptor.access = ProfileAccess::Unavailable;
        }
        descriptors.push(descriptor);
    }
    descriptors.sort_by_key(|descriptor| descriptor.profile_id);
    let observed: std::collections::BTreeSet<u64> = existing.into_iter().collect();
    let mut removed: Vec<u64> = previous_kinds
        .iter()
        .map(|(id, _)| *id)
        .filter(|id| !observed.contains(id))
        .collect();
    removed.sort_unstable();
    removed.dedup();
    ReconciledProfiles {
        descriptors,
        state: next,
        removed,
    }
}

/// Map one profile's observations to a typed descriptor.
pub fn classify_profile(
    profile_id: u64,
    owner: bool,
    user_type: Option<UserTypeHint>,
    quiet: bool,
    running: bool,
    unlocked: bool,
    previous_kind: Option<ProfileKind>,
) -> ProfileDescriptor {
    let kind = if owner {
        ProfileKind::Personal
    } else {
        match user_type {
            Some(UserTypeHint::Managed) => ProfileKind::Work,
            Some(UserTypeHint::Private) => ProfileKind::Private,
            Some(UserTypeHint::Other) => ProfileKind::Other,
            None => previous_kind.unwrap_or(ProfileKind::Other),
        }
    };
    let access = if quiet && kind == ProfileKind::Private {
        ProfileAccess::Locked
    } else if quiet {
        ProfileAccess::Quiet
    } else if !running || !unlocked {
        ProfileAccess::Unavailable
    } else {
        ProfileAccess::Available
    };
    ProfileDescriptor {
        profile_id,
        kind,
        access,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn owner_is_personal_regardless_of_lookup() {
        assert_eq!(
            classify_profile(0, true, None, false, true, true, None),
            ProfileDescriptor {
                profile_id: 0,
                kind: ProfileKind::Personal,
                access: ProfileAccess::Available,
            }
        );
    }

    #[test]
    fn managed_and_private_types_map_to_work_and_private() {
        assert_eq!(
            classify_profile(
                10,
                false,
                Some(UserTypeHint::Managed),
                true,
                true,
                false,
                None
            )
            .kind,
            ProfileKind::Work
        );
        let private = classify_profile(
            11,
            false,
            Some(UserTypeHint::Private),
            true,
            true,
            false,
            None,
        );
        assert_eq!(private.kind, ProfileKind::Private);
        assert_eq!(private.access, ProfileAccess::Locked);
    }

    #[test]
    fn unknown_type_falls_back_to_other() {
        let descriptor = classify_profile(
            12,
            false,
            Some(UserTypeHint::Other),
            false,
            false,
            false,
            None,
        );
        assert_eq!(descriptor.kind, ProfileKind::Other);
        assert_eq!(descriptor.access, ProfileAccess::Unavailable);
    }

    #[test]
    fn failed_lookup_keeps_previous_kind() {
        assert_eq!(
            classify_profile(
                10,
                false,
                None,
                false,
                false,
                false,
                Some(ProfileKind::Work)
            )
            .kind,
            ProfileKind::Work
        );
        assert_eq!(
            classify_profile(
                11,
                false,
                None,
                false,
                false,
                false,
                Some(ProfileKind::Private)
            )
            .kind,
            ProfileKind::Private
        );
        assert_eq!(
            classify_profile(12, false, None, false, false, false, None).kind,
            ProfileKind::Other
        );
    }

    #[test]
    fn repeated_failures_stay_uncertain_until_authoritative() {
        let mut state = DiscoveryState::default();
        let input = DiscoveryInput {
            profile_id: 77,
            owner: false,
            user_type: None,
        };
        state = update_discovery(&state, &[input], &[77]);
        assert_eq!(state.uncertain, vec![77]);
        assert!(state.authoritative.is_empty());
        // Second consecutive failure: still uncertain, never authoritative.
        state = update_discovery(&state, &[input], &[77]);
        assert_eq!(state.uncertain, vec![77]);
        assert!(state.authoritative.is_empty());
        // Successful lookup resolves the quarantine.
        state = update_discovery(
            &state,
            &[DiscoveryInput {
                profile_id: 77,
                owner: false,
                user_type: Some(UserTypeHint::Managed),
            }],
            &[77],
        );
        assert_eq!(state.authoritative, vec![77]);
        assert!(state.uncertain.is_empty());
    }

    #[test]
    fn removed_profiles_leave_both_sets() {
        let state = update_discovery(
            &DiscoveryState {
                authoritative: vec![0],
                uncertain: vec![77],
            },
            &[],
            &[],
        );
        assert!(state.authoritative.is_empty());
        assert!(state.uncertain.is_empty());
    }

    #[test]
    fn reconcile_failed_serial_hides_and_preserves_instead_of_removing() {
        let state = DiscoveryState {
            authoritative: vec![10],
            uncertain: vec![],
        };
        let reconciled = reconcile_profiles(
            &state,
            &[ProfileObservation {
                profile_id: 10,
                owner: false,
                user_type: None,
                quiet: false,
                running: false,
                unlocked: false,
                serial_resolved: false,
            }],
            &[(10, ProfileKind::Work)],
        );
        assert_eq!(
            reconciled.descriptors,
            vec![ProfileDescriptor {
                profile_id: 10,
                kind: ProfileKind::Work,
                access: ProfileAccess::Unavailable,
            }]
        );
        assert!(reconciled.removed.is_empty());
        assert!(reconciled.state.authoritative.contains(&10));
    }

    #[test]
    fn reconcile_unresolved_profile_never_publishes_availability() {
        // Flags observed alongside a failed lookup claim the profile is fully
        // usable; the reconciler must not trust them.
        let reconciled = reconcile_profiles(
            &DiscoveryState {
                authoritative: vec![10],
                uncertain: vec![],
            },
            &[ProfileObservation {
                profile_id: 10,
                owner: false,
                user_type: None,
                quiet: false,
                running: true,
                unlocked: true,
                serial_resolved: false,
            }],
            &[(10, ProfileKind::Work)],
        );
        assert_eq!(
            reconciled.descriptors,
            vec![ProfileDescriptor {
                profile_id: 10,
                kind: ProfileKind::Work,
                access: ProfileAccess::Unavailable,
            }]
        );
        assert!(reconciled.removed.is_empty());
    }

    #[test]
    fn reconcile_never_seen_unresolved_profile_stays_hidden_and_uncertain() {
        let reconciled = reconcile_profiles(
            &DiscoveryState::default(),
            &[ProfileObservation {
                profile_id: 77,
                owner: false,
                user_type: None,
                quiet: false,
                running: false,
                unlocked: false,
                serial_resolved: false,
            }],
            &[],
        );
        assert_eq!(
            reconciled.descriptors,
            vec![ProfileDescriptor {
                profile_id: 77,
                kind: ProfileKind::Other,
                access: ProfileAccess::Unavailable,
            }]
        );
        // Present but unresolved: not removable, and still quarantined.
        assert!(reconciled.removed.is_empty());
        assert_eq!(reconciled.state.uncertain, vec![77]);
    }

    #[test]
    fn reconcile_lists_only_genuinely_absent_serials_as_removed() {
        let reconciled = reconcile_profiles(
            &DiscoveryState::default(),
            &[ProfileObservation {
                profile_id: 10,
                owner: false,
                user_type: Some(UserTypeHint::Managed),
                quiet: false,
                running: true,
                unlocked: true,
                serial_resolved: true,
            }],
            &[(10, ProfileKind::Work), (11, ProfileKind::Private)],
        );
        assert_eq!(reconciled.removed, vec![11]);
        assert!(
            reconciled
                .descriptors
                .iter()
                .any(|descriptor| descriptor.profile_id == 10
                    && descriptor.access == ProfileAccess::Available)
        );
    }

    #[test]
    fn reconcile_generated_sequences_preserve_invariants() {
        // Deterministic xorshift: no new dependencies, reproducible locally and in CI.
        let mut seed = 0x9E3779B97F4A7C15u64;
        let mut next = || {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            seed
        };
        let mut state = DiscoveryState::default();
        let mut previous: Vec<(u64, ProfileKind)> = Vec::new();
        for round in 0..50 {
            let mut observations = Vec::new();
            for _ in 0..(next() % 4) {
                let id = next() % 5;
                observations.push(ProfileObservation {
                    profile_id: id,
                    owner: id == 0,
                    user_type: match next() % 4 {
                        0 => Some(UserTypeHint::Managed),
                        1 => Some(UserTypeHint::Private),
                        2 => Some(UserTypeHint::Other),
                        _ => None,
                    },
                    quiet: next() % 2 == 0,
                    running: next() % 2 == 0,
                    unlocked: next() % 2 == 0,
                    serial_resolved: next() % 3 != 0,
                });
            }
            observations.sort_by_key(|o| o.profile_id);
            observations.dedup_by_key(|o| o.profile_id);
            let reconciled = reconcile_profiles(&state, &observations, &previous);
            let ids: Vec<u64> = reconciled
                .descriptors
                .iter()
                .map(|d| d.profile_id)
                .collect();
            let mut sorted = ids.clone();
            sorted.sort_unstable();
            assert_eq!(ids, sorted, "descriptors sorted (round {round})");
            let observed: std::collections::BTreeSet<u64> = ids.iter().copied().collect();
            for id in &reconciled.removed {
                assert!(
                    !observed.contains(id),
                    "present serial removed (round {round})"
                );
                assert!(
                    previous.iter().any(|(prior, _)| prior == id),
                    "unknown serial removed (round {round})"
                );
            }
            for id in &reconciled.state.uncertain {
                assert!(
                    !reconciled.state.authoritative.contains(id),
                    "quarantine overlap (round {round})"
                );
            }
            // Unresolved sightings never invent removals of observed serials.
            state = reconciled.state;
            previous = reconciled
                .descriptors
                .iter()
                .map(|d| (d.profile_id, d.kind))
                .collect();
        }
    }

    #[test]
    fn previous_kind_requires_authoritative_state() {
        let kinds = vec![(77u64, ProfileKind::Work)];
        assert_eq!(
            previous_kind(
                &DiscoveryState {
                    authoritative: vec![77],
                    uncertain: vec![],
                },
                &kinds,
                77
            ),
            Some(ProfileKind::Work)
        );
        assert_eq!(
            previous_kind(
                &DiscoveryState {
                    authoritative: vec![],
                    uncertain: vec![77],
                },
                &kinds,
                77
            ),
            None
        );
    }
}
