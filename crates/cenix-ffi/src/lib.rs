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

#[derive(uniffi::Record)]
pub struct WorkspaceItem {
    pub package: String,
    pub class: String,
    pub profile_id: u64,
    pub screen: i32,
    pub cell_x: i32,
    pub cell_y: i32,
}

#[derive(uniffi::Record)]
pub struct WorkspaceSnapshot {
    pub items: Vec<WorkspaceItem>,
    pub cols: i32,
    pub rows: i32,
    pub screens: i32,
}

#[derive(uniffi::Enum)]
pub enum WorkspaceCommand {
    Place {
        package: String,
        class: String,
        profile_id: u64,
        screen: i32,
        cell_x: i32,
        cell_y: i32,
    },
    Remove {
        package: String,
        class: String,
        profile_id: u64,
    },
    Dock {
        package: String,
        class: String,
        profile_id: u64,
    },
    Pin {
        package: String,
        class: String,
        profile_id: u64,
        preferred_screen: i32,
    },
    DropMissing {
        live: Vec<AppId>,
    },
}

#[derive(uniffi::Record)]
pub struct WorkspaceTransition {
    pub items: Vec<WorkspaceItem>,
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
    #[error("invalid profile")]
    InvalidProfile,
    #[error("full")]
    Full,
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
            core::WorkspaceError::InvalidProfile => Self::InvalidProfile,
            core::WorkspaceError::Full => Self::Full,
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
    let snapshot = core::WorkspaceSnapshot {
        items: snapshot
            .items
            .into_iter()
            .map(|item| core::WorkspaceItem {
                package: item.package,
                class: item.class,
                profile_id: item.profile_id,
                screen: item.screen,
                cell_x: item.cell_x,
                cell_y: item.cell_y,
            })
            .collect(),
        cols: snapshot.cols,
        rows: snapshot.rows,
        screens: snapshot.screens,
    };
    let command = match command {
        WorkspaceCommand::Place {
            package,
            class,
            profile_id,
            screen,
            cell_x,
            cell_y,
        } => core::WorkspaceCommand::Place {
            package,
            class,
            profile_id,
            screen,
            cell_x,
            cell_y,
        },
        WorkspaceCommand::Remove {
            package,
            class,
            profile_id,
        } => core::WorkspaceCommand::Remove {
            package,
            class,
            profile_id,
        },
        WorkspaceCommand::Dock {
            package,
            class,
            profile_id,
        } => core::WorkspaceCommand::Dock {
            package,
            class,
            profile_id,
        },
        WorkspaceCommand::Pin {
            package,
            class,
            profile_id,
            preferred_screen,
        } => core::WorkspaceCommand::Pin {
            package,
            class,
            profile_id,
            preferred_screen,
        },
        WorkspaceCommand::DropMissing { live } => core::WorkspaceCommand::DropMissing {
            live: live
                .into_iter()
                .map(|id| (id.package, id.class, id.profile_id))
                .collect(),
        },
    };
    let transition = core::apply_workspace_command(snapshot, command)?;
    Ok(WorkspaceTransition {
        items: transition
            .items
            .into_iter()
            .map(|item| WorkspaceItem {
                package: item.package,
                class: item.class,
                profile_id: item.profile_id,
                screen: item.screen,
                cell_x: item.cell_x,
                cell_y: item.cell_y,
            })
            .collect(),
    })
}
