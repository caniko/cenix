#![forbid(unsafe_code)]

mod workspace;

pub use workspace::{
    CellRect, ComponentId, ContainerRef, Folder, FolderMember, GridSpec, ItemPayload, ShortcutId,
    WidgetMinimumSpan, WidgetProviderId, WorkspaceCommand, WorkspaceError, WorkspaceItem,
    WorkspacePage, WorkspaceSnapshot, WorkspaceTransition, apply_workspace_command,
};

pub const MAX_APPLICATIONS: usize = 10_000;
pub const MAX_LABEL_CHARS: usize = 256;
pub const MAX_IDENT_CHARS: usize = 256;
pub const MAX_QUERY_CHARS: usize = 256;
pub const MAX_VISIBLE_PROFILES: usize = 64;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProfileKind {
    Personal,
    Work,
    Private,
    Other,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProfileAccess {
    Available,
    Quiet,
    Locked,
    Unavailable,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ProfileDescriptor {
    pub profile_id: u64,
    pub kind: ProfileKind,
    pub access: ProfileAccess,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProfileSurface {
    AllApps,
    Search,
    Workspace,
    Shortcut,
    Widget,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProfileItemProjection {
    Visible,
    Placeholder,
    Hidden,
}

pub fn project_profile_item(
    profile: ProfileDescriptor,
    surface: ProfileSurface,
) -> ProfileItemProjection {
    if profile.kind == ProfileKind::Private && surface == ProfileSurface::Workspace {
        return ProfileItemProjection::Hidden;
    }
    match profile.access {
        ProfileAccess::Available => ProfileItemProjection::Visible,
        ProfileAccess::Quiet | ProfileAccess::Unavailable
            if profile.kind != ProfileKind::Private && surface == ProfileSurface::Workspace =>
        {
            ProfileItemProjection::Placeholder
        }
        ProfileAccess::Quiet | ProfileAccess::Locked | ProfileAccess::Unavailable => {
            ProfileItemProjection::Hidden
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct App {
    pub package: String,
    pub class: String,
    pub profile_id: u64,
    pub label: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AppId {
    pub package: String,
    pub class: String,
    pub profile_id: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EngineError {
    Malformed(&'static str),
    Bounds(&'static str),
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd)]
enum Rank {
    Exact = 0,
    Prefix = 1,
    TokenPrefix = 2,
    Substring = 3,
}

pub fn filter_and_order_apps(
    apps: Vec<App>,
    query: &str,
    visible_profile_ids: &[u64],
) -> Result<Vec<AppId>, EngineError> {
    validate(&apps, query, visible_profile_ids)?;
    Ok(rank_apps(&apps, query, visible_profile_ids))
}

fn validate(apps: &[App], query: &str, visible_profile_ids: &[u64]) -> Result<(), EngineError> {
    if apps.len() > MAX_APPLICATIONS {
        return Err(EngineError::Bounds("too many applications"));
    }
    if visible_profile_ids.len() > MAX_VISIBLE_PROFILES {
        return Err(EngineError::Bounds("too many visible profiles"));
    }
    if query.chars().count() > MAX_QUERY_CHARS {
        return Err(EngineError::Bounds("query exceeds maximum length"));
    }
    for app in apps {
        if app.package.chars().count() > MAX_IDENT_CHARS
            || app.class.chars().count() > MAX_IDENT_CHARS
        {
            return Err(EngineError::Bounds(
                "package or class exceeds maximum length",
            ));
        }
        if app.label.chars().count() > MAX_LABEL_CHARS {
            return Err(EngineError::Bounds("label exceeds maximum length"));
        }
        if app.package.is_empty() || app.class.is_empty() {
            return Err(EngineError::Malformed("package and class are required"));
        }
    }
    Ok(())
}

fn rank_apps(apps: &[App], query: &str, visible_profile_ids: &[u64]) -> Vec<AppId> {
    let query = normalize(query);
    let mut scored: Vec<(Rank, String, &App)> = apps
        .iter()
        .filter(|app| visible_profile_ids.contains(&app.profile_id))
        .filter_map(|app| {
            let label = normalize(&app.label);
            let rank = if query.is_empty() {
                Some(Rank::Exact)
            } else {
                rank_label(&label, &query)
            };
            rank.map(|rank| (rank, label, app))
        })
        .collect();
    scored.sort_by(|a, b| {
        a.0.cmp(&b.0)
            .then_with(|| a.1.cmp(&b.1))
            .then_with(|| a.2.package.cmp(&b.2.package))
            .then_with(|| a.2.class.cmp(&b.2.class))
            .then_with(|| a.2.profile_id.cmp(&b.2.profile_id))
    });
    scored
        .into_iter()
        .map(|(_, _, app)| AppId {
            package: app.package.clone(),
            class: app.class.clone(),
            profile_id: app.profile_id,
        })
        .collect()
}

fn rank_label(label: &str, query: &str) -> Option<Rank> {
    if label == query {
        return Some(Rank::Exact);
    }
    if label.starts_with(query) {
        return Some(Rank::Prefix);
    }
    if label
        .split_whitespace()
        .any(|token| token.starts_with(query))
    {
        return Some(Rank::TokenPrefix);
    }
    if label.contains(query) {
        return Some(Rank::Substring);
    }
    None
}

fn normalize(value: &str) -> String {
    value
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .to_lowercase()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn app(package: &str, class: &str, profile: u64, label: &str) -> App {
        App {
            package: package.to_string(),
            class: class.to_string(),
            profile_id: profile,
            label: label.to_string(),
        }
    }

    fn names(ids: Vec<AppId>) -> Vec<String> {
        ids.into_iter().map(|item| item.package).collect()
    }

    #[test]
    fn empty_query_returns_visible_apps_sorted() {
        let apps = vec![
            app("b.pkg", "B", 0, "Bravo"),
            app("a.pkg", "A", 0, "Alpha"),
            app("hidden.pkg", "H", 1, "Hidden"),
        ];
        assert_eq!(
            names(filter_and_order_apps(apps, "", &[0]).unwrap()),
            ["a.pkg", "b.pkg"]
        );
    }

    #[test]
    fn exact_match_outranks_prefix() {
        let apps = vec![
            app("prefix.pkg", "P", 0, "Mailbox"),
            app("exact.pkg", "E", 0, "Mail"),
        ];
        assert_eq!(
            names(filter_and_order_apps(apps, "mail", &[0]).unwrap()),
            ["exact.pkg", "prefix.pkg"]
        );
    }

    #[test]
    fn prefix_and_token_and_substring() {
        let apps = vec![
            app("sub.pkg", "S", 0, "My Camera"),
            app("pre.pkg", "P", 0, "Camera"),
            app("tok.pkg", "T", 0, "Open Camera"),
            app("mid.pkg", "M", 0, "Webcam"),
        ];
        assert_eq!(
            names(filter_and_order_apps(apps, "cam", &[0]).unwrap()),
            ["pre.pkg", "sub.pkg", "tok.pkg", "mid.pkg"]
        );
    }

    #[test]
    fn deterministic_ties_use_package_then_class() {
        let apps = vec![
            app("z.pkg", "Z", 0, "Same"),
            app("a.pkg", "B", 0, "Same"),
            app("a.pkg", "A", 0, "Same"),
        ];
        let first = names(filter_and_order_apps(apps.clone(), "same", &[0]).unwrap());
        let second = names(filter_and_order_apps(apps, "same", &[0]).unwrap());
        assert_eq!(first, ["a.pkg", "a.pkg", "z.pkg"]);
        assert_eq!(first, second);
    }

    #[test]
    fn hidden_profiles_are_excluded() {
        let apps = vec![
            app("vis.pkg", "V", 0, "Visible"),
            app("hid.pkg", "H", 9, "Hidden"),
        ];
        assert_eq!(
            names(filter_and_order_apps(apps, "", &[0]).unwrap()),
            ["vis.pkg"]
        );
    }

    #[test]
    fn oversized_query_is_rejected() {
        let err =
            filter_and_order_apps(vec![], &"q".repeat(MAX_QUERY_CHARS + 1), &[0]).unwrap_err();
        assert_eq!(err, EngineError::Bounds("query exceeds maximum length"));
    }

    #[test]
    fn empty_id_is_rejected() {
        let err = filter_and_order_apps(vec![app("", "C", 0, "X")], "", &[0]).unwrap_err();
        assert_eq!(
            err,
            EngineError::Malformed("package and class are required")
        );
    }

    #[test]
    fn profile_projection_hides_locked_identity_and_preserves_work_placeholder() {
        let private = ProfileDescriptor {
            profile_id: 2,
            kind: ProfileKind::Private,
            access: ProfileAccess::Locked,
        };
        let work = ProfileDescriptor {
            profile_id: 1,
            kind: ProfileKind::Work,
            access: ProfileAccess::Quiet,
        };
        assert_eq!(
            project_profile_item(private, ProfileSurface::Search),
            ProfileItemProjection::Hidden
        );
        assert_eq!(
            project_profile_item(private, ProfileSurface::Workspace),
            ProfileItemProjection::Hidden
        );
        assert_eq!(
            project_profile_item(work, ProfileSurface::Workspace),
            ProfileItemProjection::Placeholder
        );
        assert_eq!(
            project_profile_item(work, ProfileSurface::Shortcut),
            ProfileItemProjection::Hidden
        );
    }
}
