uniffi::setup_scaffolding!();

use cenix_core::{self as core, EngineError as CoreError};

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

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum EngineError {
    #[error("{reason}")]
    Malformed { reason: String },
    #[error("{reason}")]
    Bounds { reason: String },
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
