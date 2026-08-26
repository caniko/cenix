uniffi::setup_scaffolding!();

use std::sync::atomic::{AtomicBool, Ordering};

use cenix_core::{self as core, EngineError as CoreError};

static PANICKED: AtomicBool = AtomicBool::new(false);

#[derive(uniffi::Record)]
pub struct App {
    pub package: String,
    pub class: String,
    pub profile_id: u64,
    pub label: String,
}

#[derive(uniffi::Record)]
pub struct AppId {
    pub package: String,
    pub class: String,
    pub profile_id: u64,
}

#[derive(Clone, uniffi::Record)]
pub struct ComponentId {
    pub package: String,
    pub class: String,
    pub profile_id: u64,
}

#[derive(Clone, uniffi::Record)]
pub struct ShortcutId {
    pub package: String,
    pub shortcut_id: String,
    pub profile_id: u64,
}

#[derive(Clone, uniffi::Record)]
pub struct WidgetProviderId {
    pub package: String,
    pub class: String,
    pub profile_id: u64,
}

#[derive(Clone, Copy, uniffi::Enum)]
pub enum ProfileKind {
    Personal,
    Work,
    Private,
    Other,
}

#[derive(Clone, Copy, uniffi::Enum)]
pub enum ProfileAccess {
    Available,
    Quiet,
    Locked,
    Unavailable,
}

#[derive(Clone, Copy, uniffi::Record)]
pub struct ProfileDescriptor {
    pub profile_id: u64,
    pub kind: ProfileKind,
    pub access: ProfileAccess,
}

#[derive(Clone, Copy, uniffi::Enum)]
pub enum ProfileSurface {
    AllApps,
    Search,
    Workspace,
    Shortcut,
    Widget,
}

#[derive(Clone, Copy, uniffi::Enum)]
pub enum ProfileItemProjection {
    Visible,
    Placeholder,
    Hidden,
}

#[derive(Clone, Copy, uniffi::Record)]
pub struct GridSpec {
    pub cols: i32,
    pub rows: i32,
    pub hotseat_cols: i32,
}

#[derive(Clone, Copy, uniffi::Record)]
pub struct CellRect {
    pub cell_x: i32,
    pub cell_y: i32,
    pub span_x: i32,
    pub span_y: i32,
}

#[derive(Clone, Copy, uniffi::Record)]
pub struct WidgetMinimumSpan {
    pub item_id: u64,
    pub span_x: i32,
    pub span_y: i32,
}

#[derive(Clone, uniffi::Enum)]
pub enum ContainerRef {
    Workspace { page_id: u64 },
    Hotseat,
}

#[derive(Clone, uniffi::Enum)]
pub enum ItemPayload {
    Application { component: ComponentId },
    Folder,
    Shortcut { shortcut: ShortcutId },
    Widget { provider: WidgetProviderId },
}

#[derive(Clone, uniffi::Record)]
pub struct WorkspacePage {
    pub page_id: u64,
    pub rank: i32,
}

#[derive(Clone, uniffi::Record)]
pub struct WorkspaceItem {
    pub item_id: u64,
    pub payload: ItemPayload,
    pub container: ContainerRef,
    pub cell: CellRect,
}

#[derive(Clone, uniffi::Record)]
pub struct FolderMember {
    pub item_id: u64,
    pub payload: ItemPayload,
    pub rank: u32,
}

#[derive(Clone, uniffi::Record)]
pub struct Folder {
    pub folder_id: u64,
    pub title: String,
    pub members: Vec<FolderMember>,
}

#[derive(uniffi::Record)]
pub struct WorkspaceSnapshot {
    pub generation: u64,
    pub grid: GridSpec,
    pub pages: Vec<WorkspacePage>,
    pub items: Vec<WorkspaceItem>,
    pub folders: Vec<Folder>,
}

#[derive(uniffi::Enum)]
pub enum WorkspaceCommand {
    PlaceFromAllApps {
        expected_generation: u64,
        item_id: u64,
        component: ComponentId,
        page_id: u64,
        cell: CellRect,
    },
    PlaceShortcut {
        expected_generation: u64,
        item_id: u64,
        shortcut: ShortcutId,
        container: ContainerRef,
        cell: CellRect,
    },
    PlaceWidget {
        expected_generation: u64,
        item_id: u64,
        provider: WidgetProviderId,
        page_id: u64,
        cell: CellRect,
    },
    ResizeWidget {
        expected_generation: u64,
        item_id: u64,
        cell: CellRect,
    },
    Move {
        expected_generation: u64,
        item_id: u64,
        container: ContainerRef,
        cell: CellRect,
    },
    Remove {
        expected_generation: u64,
        item_id: u64,
    },
    Dock {
        expected_generation: u64,
        item_id: u64,
        rank: i32,
    },
    Undock {
        expected_generation: u64,
        item_id: u64,
        page_id: u64,
        cell: CellRect,
    },
    Reorder {
        expected_generation: u64,
        item_id: u64,
        container: ContainerRef,
        cell: CellRect,
    },
    CreateFolder {
        expected_generation: u64,
        folder_id: u64,
        first_item_id: u64,
        second_item_id: u64,
    },
    AddFromAllAppsToFolder {
        expected_generation: u64,
        item_id: u64,
        component: ComponentId,
        folder_id: u64,
        rank: u32,
    },
    AddShortcutToFolder {
        expected_generation: u64,
        item_id: u64,
        shortcut: ShortcutId,
        folder_id: u64,
        rank: u32,
    },
    AddItemToFolder {
        expected_generation: u64,
        item_id: u64,
        folder_id: u64,
        rank: u32,
    },
    MoveFolderMember {
        expected_generation: u64,
        folder_id: u64,
        item_id: u64,
        rank: u32,
    },
    RemoveItemFromFolder {
        expected_generation: u64,
        folder_id: u64,
        item_id: u64,
        container: ContainerRef,
        cell: CellRect,
    },
    RenameFolder {
        expected_generation: u64,
        folder_id: u64,
        title: String,
    },
    AddPage {
        expected_generation: u64,
        page_id: u64,
    },
    RemoveEmptyPage {
        expected_generation: u64,
        page_id: u64,
    },
    SetGrid {
        expected_generation: u64,
        grid: GridSpec,
        next_page_id: u64,
        widget_minimum_spans: Vec<WidgetMinimumSpan>,
    },
    DropMissing {
        expected_generation: u64,
        live: Vec<ComponentId>,
        authoritative_profile_ids: Vec<u64>,
    },
    ReconcileShortcuts {
        expected_generation: u64,
        live: Vec<ShortcutId>,
        authoritative_profile_ids: Vec<u64>,
    },
    RemoveProfiles {
        expected_generation: u64,
        profile_ids: Vec<u64>,
    },
    Cancelled {
        expected_generation: u64,
    },
}

#[derive(uniffi::Record)]
pub struct WorkspaceTransition {
    pub generation: u64,
    pub grid: GridSpec,
    pub pages: Vec<WorkspacePage>,
    pub items: Vec<WorkspaceItem>,
    pub folders: Vec<Folder>,
    pub created_page_ids: Vec<u64>,
    pub removed_page_ids: Vec<u64>,
    pub changed_item_ids: Vec<u64>,
}

#[derive(uniffi::Record)]
pub struct DiagnosticsConfig {
    pub level: String,
    pub release_redaction: bool,
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum EngineError {
    #[error("{reason}")]
    Malformed { reason: String },
    #[error("{reason}")]
    Bounds { reason: String },
}

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum WorkspaceError {
    #[error("occupied")]
    Occupied,
    #[error("out of bounds")]
    OutOfBounds,
    #[error("missing item")]
    MissingItem,
    #[error("missing page")]
    MissingPage,
    #[error("missing folder")]
    MissingFolder,
    #[error("invalid profile")]
    InvalidProfile,
    #[error("full")]
    Full,
    #[error("stale generation")]
    StaleGeneration,
    #[error("invalid grid")]
    InvalidGrid,
    #[error("invalid title")]
    InvalidTitle,
    #[error("cross profile")]
    CrossProfile,
    #[error("widget is too large for grid")]
    WidgetTooLarge,
    #[error("invariant violation")]
    InvariantViolation,
}

impl From<CoreError> for EngineError {
    fn from(error: CoreError) -> Self {
        match error {
            CoreError::Malformed(reason) => Self::Malformed {
                reason: reason.to_string(),
            },
            CoreError::Bounds(reason) => Self::Bounds {
                reason: reason.to_string(),
            },
        }
    }
}

impl From<core::WorkspaceError> for WorkspaceError {
    fn from(error: core::WorkspaceError) -> Self {
        match error {
            core::WorkspaceError::Occupied => Self::Occupied,
            core::WorkspaceError::OutOfBounds => Self::OutOfBounds,
            core::WorkspaceError::MissingItem => Self::MissingItem,
            core::WorkspaceError::MissingPage => Self::MissingPage,
            core::WorkspaceError::MissingFolder => Self::MissingFolder,
            core::WorkspaceError::InvalidProfile => Self::InvalidProfile,
            core::WorkspaceError::Full => Self::Full,
            core::WorkspaceError::StaleGeneration => Self::StaleGeneration,
            core::WorkspaceError::InvalidGrid => Self::InvalidGrid,
            core::WorkspaceError::InvalidTitle => Self::InvalidTitle,
            core::WorkspaceError::CrossProfile => Self::CrossProfile,
            core::WorkspaceError::WidgetTooLarge => Self::WidgetTooLarge,
            core::WorkspaceError::InvariantViolation => Self::InvariantViolation,
        }
    }
}

#[uniffi::export]
pub fn init_diagnostics(_config: DiagnosticsConfig) {
    let _ = std::panic::take_hook();
    std::panic::set_hook(Box::new(|_| {
        PANICKED.store(true, Ordering::SeqCst);
    }));
}

#[uniffi::export]
pub fn native_panicked() -> bool {
    PANICKED.load(Ordering::SeqCst)
}

#[uniffi::export]
pub fn filter_and_order_apps(
    apps: Vec<App>,
    query: String,
    visible_profile_ids: Vec<u64>,
) -> Result<Vec<AppId>, EngineError> {
    let apps = apps
        .into_iter()
        .map(|app| core::App {
            package: app.package,
            class: app.class,
            profile_id: app.profile_id,
            label: app.label,
        })
        .collect();
    Ok(
        core::filter_and_order_apps(apps, &query, &visible_profile_ids)?
            .into_iter()
            .map(|id| AppId {
                package: id.package,
                class: id.class,
                profile_id: id.profile_id,
            })
            .collect(),
    )
}

#[uniffi::export]
pub fn project_profile_item(
    profile: ProfileDescriptor,
    surface: ProfileSurface,
) -> ProfileItemProjection {
    core::project_profile_item(profile.into(), surface.into()).into()
}

#[uniffi::export]
pub fn apply_workspace_command(
    snapshot: WorkspaceSnapshot,
    command: WorkspaceCommand,
) -> Result<WorkspaceTransition, WorkspaceError> {
    let transition = core::apply_workspace_command(snapshot.into(), command.into())?;
    Ok(transition.into())
}

impl From<ComponentId> for core::ComponentId {
    fn from(value: ComponentId) -> Self {
        Self {
            package: value.package,
            class: value.class,
            profile_id: value.profile_id,
        }
    }
}

impl From<core::ComponentId> for ComponentId {
    fn from(value: core::ComponentId) -> Self {
        Self {
            package: value.package,
            class: value.class,
            profile_id: value.profile_id,
        }
    }
}

impl From<ShortcutId> for core::ShortcutId {
    fn from(value: ShortcutId) -> Self {
        Self {
            package: value.package,
            shortcut_id: value.shortcut_id,
            profile_id: value.profile_id,
        }
    }
}

impl From<core::ShortcutId> for ShortcutId {
    fn from(value: core::ShortcutId) -> Self {
        Self {
            package: value.package,
            shortcut_id: value.shortcut_id,
            profile_id: value.profile_id,
        }
    }
}

impl From<WidgetProviderId> for core::WidgetProviderId {
    fn from(value: WidgetProviderId) -> Self {
        Self {
            package: value.package,
            class: value.class,
            profile_id: value.profile_id,
        }
    }
}

impl From<core::WidgetProviderId> for WidgetProviderId {
    fn from(value: core::WidgetProviderId) -> Self {
        Self {
            package: value.package,
            class: value.class,
            profile_id: value.profile_id,
        }
    }
}

impl From<ProfileDescriptor> for core::ProfileDescriptor {
    fn from(value: ProfileDescriptor) -> Self {
        Self {
            profile_id: value.profile_id,
            kind: match value.kind {
                ProfileKind::Personal => core::ProfileKind::Personal,
                ProfileKind::Work => core::ProfileKind::Work,
                ProfileKind::Private => core::ProfileKind::Private,
                ProfileKind::Other => core::ProfileKind::Other,
            },
            access: match value.access {
                ProfileAccess::Available => core::ProfileAccess::Available,
                ProfileAccess::Quiet => core::ProfileAccess::Quiet,
                ProfileAccess::Locked => core::ProfileAccess::Locked,
                ProfileAccess::Unavailable => core::ProfileAccess::Unavailable,
            },
        }
    }
}

impl From<ProfileSurface> for core::ProfileSurface {
    fn from(value: ProfileSurface) -> Self {
        match value {
            ProfileSurface::AllApps => Self::AllApps,
            ProfileSurface::Search => Self::Search,
            ProfileSurface::Workspace => Self::Workspace,
            ProfileSurface::Shortcut => Self::Shortcut,
            ProfileSurface::Widget => Self::Widget,
        }
    }
}

impl From<core::ProfileItemProjection> for ProfileItemProjection {
    fn from(value: core::ProfileItemProjection) -> Self {
        match value {
            core::ProfileItemProjection::Visible => Self::Visible,
            core::ProfileItemProjection::Placeholder => Self::Placeholder,
            core::ProfileItemProjection::Hidden => Self::Hidden,
        }
    }
}

impl From<GridSpec> for core::GridSpec {
    fn from(value: GridSpec) -> Self {
        Self {
            cols: value.cols,
            rows: value.rows,
            hotseat_cols: value.hotseat_cols,
        }
    }
}

impl From<core::GridSpec> for GridSpec {
    fn from(value: core::GridSpec) -> Self {
        Self {
            cols: value.cols,
            rows: value.rows,
            hotseat_cols: value.hotseat_cols,
        }
    }
}

impl From<CellRect> for core::CellRect {
    fn from(value: CellRect) -> Self {
        Self {
            cell_x: value.cell_x,
            cell_y: value.cell_y,
            span_x: value.span_x,
            span_y: value.span_y,
        }
    }
}

impl From<core::CellRect> for CellRect {
    fn from(value: core::CellRect) -> Self {
        Self {
            cell_x: value.cell_x,
            cell_y: value.cell_y,
            span_x: value.span_x,
            span_y: value.span_y,
        }
    }
}

impl From<WidgetMinimumSpan> for core::WidgetMinimumSpan {
    fn from(value: WidgetMinimumSpan) -> Self {
        Self {
            item_id: value.item_id,
            span_x: value.span_x,
            span_y: value.span_y,
        }
    }
}

impl From<ContainerRef> for core::ContainerRef {
    fn from(value: ContainerRef) -> Self {
        match value {
            ContainerRef::Workspace { page_id } => Self::Workspace { page_id },
            ContainerRef::Hotseat => Self::Hotseat,
        }
    }
}

impl From<core::ContainerRef> for ContainerRef {
    fn from(value: core::ContainerRef) -> Self {
        match value {
            core::ContainerRef::Workspace { page_id } => Self::Workspace { page_id },
            core::ContainerRef::Hotseat => Self::Hotseat,
        }
    }
}

impl From<ItemPayload> for core::ItemPayload {
    fn from(value: ItemPayload) -> Self {
        match value {
            ItemPayload::Application { component } => Self::Application(component.into()),
            ItemPayload::Folder => Self::Folder,
            ItemPayload::Shortcut { shortcut } => Self::Shortcut(shortcut.into()),
            ItemPayload::Widget { provider } => Self::Widget(provider.into()),
        }
    }
}

impl From<core::ItemPayload> for ItemPayload {
    fn from(value: core::ItemPayload) -> Self {
        match value {
            core::ItemPayload::Application(component) => Self::Application {
                component: component.into(),
            },
            core::ItemPayload::Folder => Self::Folder,
            core::ItemPayload::Shortcut(shortcut) => Self::Shortcut {
                shortcut: shortcut.into(),
            },
            core::ItemPayload::Widget(provider) => Self::Widget {
                provider: provider.into(),
            },
        }
    }
}

impl From<WorkspacePage> for core::WorkspacePage {
    fn from(value: WorkspacePage) -> Self {
        Self {
            page_id: value.page_id,
            rank: value.rank,
        }
    }
}

impl From<core::WorkspacePage> for WorkspacePage {
    fn from(value: core::WorkspacePage) -> Self {
        Self {
            page_id: value.page_id,
            rank: value.rank,
        }
    }
}

impl From<WorkspaceItem> for core::WorkspaceItem {
    fn from(value: WorkspaceItem) -> Self {
        Self {
            item_id: value.item_id,
            payload: value.payload.into(),
            container: value.container.into(),
            cell: value.cell.into(),
        }
    }
}

impl From<core::WorkspaceItem> for WorkspaceItem {
    fn from(value: core::WorkspaceItem) -> Self {
        Self {
            item_id: value.item_id,
            payload: value.payload.into(),
            container: value.container.into(),
            cell: value.cell.into(),
        }
    }
}

impl From<FolderMember> for core::FolderMember {
    fn from(value: FolderMember) -> Self {
        Self {
            item_id: value.item_id,
            payload: value.payload.into(),
            rank: value.rank,
        }
    }
}

impl From<core::FolderMember> for FolderMember {
    fn from(value: core::FolderMember) -> Self {
        Self {
            item_id: value.item_id,
            payload: value.payload.into(),
            rank: value.rank,
        }
    }
}

impl From<Folder> for core::Folder {
    fn from(value: Folder) -> Self {
        Self {
            folder_id: value.folder_id,
            title: value.title,
            members: value.members.into_iter().map(Into::into).collect(),
        }
    }
}

impl From<core::Folder> for Folder {
    fn from(value: core::Folder) -> Self {
        Self {
            folder_id: value.folder_id,
            title: value.title,
            members: value.members.into_iter().map(Into::into).collect(),
        }
    }
}

impl From<WorkspaceSnapshot> for core::WorkspaceSnapshot {
    fn from(value: WorkspaceSnapshot) -> Self {
        Self {
            generation: value.generation,
            grid: value.grid.into(),
            pages: value.pages.into_iter().map(Into::into).collect(),
            items: value.items.into_iter().map(Into::into).collect(),
            folders: value.folders.into_iter().map(Into::into).collect(),
        }
    }
}

impl From<WorkspaceCommand> for core::WorkspaceCommand {
    fn from(value: WorkspaceCommand) -> Self {
        match value {
            WorkspaceCommand::PlaceFromAllApps {
                expected_generation,
                item_id,
                component,
                page_id,
                cell,
            } => Self::PlaceFromAllApps {
                expected_generation,
                item_id,
                component: component.into(),
                page_id,
                cell: cell.into(),
            },
            WorkspaceCommand::PlaceShortcut {
                expected_generation,
                item_id,
                shortcut,
                container,
                cell,
            } => Self::PlaceShortcut {
                expected_generation,
                item_id,
                shortcut: shortcut.into(),
                container: container.into(),
                cell: cell.into(),
            },
            WorkspaceCommand::PlaceWidget {
                expected_generation,
                item_id,
                provider,
                page_id,
                cell,
            } => Self::PlaceWidget {
                expected_generation,
                item_id,
                provider: provider.into(),
                page_id,
                cell: cell.into(),
            },
            WorkspaceCommand::ResizeWidget {
                expected_generation,
                item_id,
                cell,
            } => Self::ResizeWidget {
                expected_generation,
                item_id,
                cell: cell.into(),
            },
            WorkspaceCommand::Move {
                expected_generation,
                item_id,
                container,
                cell,
            } => Self::Move {
                expected_generation,
                item_id,
                container: container.into(),
                cell: cell.into(),
            },
            WorkspaceCommand::Remove {
                expected_generation,
                item_id,
            } => Self::Remove {
                expected_generation,
                item_id,
            },
            WorkspaceCommand::Dock {
                expected_generation,
                item_id,
                rank,
            } => Self::Dock {
                expected_generation,
                item_id,
                rank,
            },
            WorkspaceCommand::Undock {
                expected_generation,
                item_id,
                page_id,
                cell,
            } => Self::Undock {
                expected_generation,
                item_id,
                page_id,
                cell: cell.into(),
            },
            WorkspaceCommand::Reorder {
                expected_generation,
                item_id,
                container,
                cell,
            } => Self::Reorder {
                expected_generation,
                item_id,
                container: container.into(),
                cell: cell.into(),
            },
            WorkspaceCommand::CreateFolder {
                expected_generation,
                folder_id,
                first_item_id,
                second_item_id,
            } => Self::CreateFolder {
                expected_generation,
                folder_id,
                first_item_id,
                second_item_id,
            },
            WorkspaceCommand::AddFromAllAppsToFolder {
                expected_generation,
                item_id,
                component,
                folder_id,
                rank,
            } => Self::AddFromAllAppsToFolder {
                expected_generation,
                item_id,
                component: component.into(),
                folder_id,
                rank,
            },
            WorkspaceCommand::AddShortcutToFolder {
                expected_generation,
                item_id,
                shortcut,
                folder_id,
                rank,
            } => Self::AddShortcutToFolder {
                expected_generation,
                item_id,
                shortcut: shortcut.into(),
                folder_id,
                rank,
            },
            WorkspaceCommand::AddItemToFolder {
                expected_generation,
                item_id,
                folder_id,
                rank,
            } => Self::AddItemToFolder {
                expected_generation,
                item_id,
                folder_id,
                rank,
            },
            WorkspaceCommand::MoveFolderMember {
                expected_generation,
                folder_id,
                item_id,
                rank,
            } => Self::MoveFolderMember {
                expected_generation,
                folder_id,
                item_id,
                rank,
            },
            WorkspaceCommand::RemoveItemFromFolder {
                expected_generation,
                folder_id,
                item_id,
                container,
                cell,
            } => Self::RemoveItemFromFolder {
                expected_generation,
                folder_id,
                item_id,
                container: container.into(),
                cell: cell.into(),
            },
            WorkspaceCommand::RenameFolder {
                expected_generation,
                folder_id,
                title,
            } => Self::RenameFolder {
                expected_generation,
                folder_id,
                title,
            },
            WorkspaceCommand::AddPage {
                expected_generation,
                page_id,
            } => Self::AddPage {
                expected_generation,
                page_id,
            },
            WorkspaceCommand::RemoveEmptyPage {
                expected_generation,
                page_id,
            } => Self::RemoveEmptyPage {
                expected_generation,
                page_id,
            },
            WorkspaceCommand::SetGrid {
                expected_generation,
                grid,
                next_page_id,
                widget_minimum_spans,
            } => Self::SetGrid {
                expected_generation,
                grid: grid.into(),
                next_page_id,
                widget_minimum_spans: widget_minimum_spans.into_iter().map(Into::into).collect(),
            },
            WorkspaceCommand::DropMissing {
                expected_generation,
                live,
                authoritative_profile_ids,
            } => Self::DropMissing {
                expected_generation,
                live: live.into_iter().map(Into::into).collect(),
                authoritative_profile_ids,
            },
            WorkspaceCommand::ReconcileShortcuts {
                expected_generation,
                live,
                authoritative_profile_ids,
            } => Self::ReconcileShortcuts {
                expected_generation,
                live: live.into_iter().map(Into::into).collect(),
                authoritative_profile_ids,
            },
            WorkspaceCommand::RemoveProfiles {
                expected_generation,
                profile_ids,
            } => Self::RemoveProfiles {
                expected_generation,
                profile_ids,
            },
            WorkspaceCommand::Cancelled {
                expected_generation,
            } => Self::Cancelled {
                expected_generation,
            },
        }
    }
}

impl From<core::WorkspaceTransition> for WorkspaceTransition {
    fn from(value: core::WorkspaceTransition) -> Self {
        Self {
            generation: value.generation,
            grid: value.grid.into(),
            pages: value.pages.into_iter().map(Into::into).collect(),
            items: value.items.into_iter().map(Into::into).collect(),
            folders: value.folders.into_iter().map(Into::into).collect(),
            created_page_ids: value.created_page_ids,
            removed_page_ids: value.removed_page_ids,
            changed_item_ids: value.changed_item_ids,
        }
    }
}
