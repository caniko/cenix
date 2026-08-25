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

#[derive(Clone, uniffi::Enum)]
pub enum ContainerRef {
    Workspace { page_id: u64 },
    Hotseat,
}

#[derive(Clone, Copy, uniffi::Enum)]
pub enum ItemKind {
    Application,
}

#[derive(Clone, uniffi::Record)]
pub struct WorkspacePage {
    pub page_id: u64,
    pub rank: i32,
}

#[derive(Clone, uniffi::Record)]
pub struct WorkspaceItem {
    pub item_id: u64,
    pub component: ComponentId,
    pub container: ContainerRef,
    pub cell: CellRect,
    pub kind: ItemKind,
}

#[derive(uniffi::Record)]
pub struct WorkspaceSnapshot {
    pub generation: u64,
    pub grid: GridSpec,
    pub pages: Vec<WorkspacePage>,
    pub items: Vec<WorkspaceItem>,
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
    },
    DropMissing {
        expected_generation: u64,
        live: Vec<ComponentId>,
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
    #[error("invalid profile")]
    InvalidProfile,
    #[error("full")]
    Full,
    #[error("stale generation")]
    StaleGeneration,
    #[error("invalid grid")]
    InvalidGrid,
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
            core::WorkspaceError::InvalidProfile => Self::InvalidProfile,
            core::WorkspaceError::Full => Self::Full,
            core::WorkspaceError::StaleGeneration => Self::StaleGeneration,
            core::WorkspaceError::InvalidGrid => Self::InvalidGrid,
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

impl From<ItemKind> for core::ItemKind {
    fn from(value: ItemKind) -> Self {
        match value {
            ItemKind::Application => Self::Application,
        }
    }
}

impl From<core::ItemKind> for ItemKind {
    fn from(value: core::ItemKind) -> Self {
        match value {
            core::ItemKind::Application => Self::Application,
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
            component: value.component.into(),
            container: value.container.into(),
            cell: value.cell.into(),
            kind: value.kind.into(),
        }
    }
}

impl From<core::WorkspaceItem> for WorkspaceItem {
    fn from(value: core::WorkspaceItem) -> Self {
        Self {
            item_id: value.item_id,
            component: value.component.into(),
            container: value.container.into(),
            cell: value.cell.into(),
            kind: value.kind.into(),
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
            } => Self::SetGrid {
                expected_generation,
                grid: grid.into(),
            },
            WorkspaceCommand::DropMissing {
                expected_generation,
                live,
            } => Self::DropMissing {
                expected_generation,
                live: live.into_iter().map(Into::into).collect(),
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
            created_page_ids: value.created_page_ids,
            removed_page_ids: value.removed_page_ids,
            changed_item_ids: value.changed_item_ids,
        }
    }
}
