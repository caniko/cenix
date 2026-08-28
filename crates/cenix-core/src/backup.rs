use std::collections::{HashMap, HashSet};

use crate::workspace::{
    CellRect, ComponentId, GridSpec, ItemPayload, ShortcutId, WidgetProviderId, WorkspaceError,
    WorkspaceSnapshot, prepare_snapshot,
};
use crate::{MAX_APPLICATIONS, MAX_IDENT_CHARS, ProfileKind};

pub const BACKUP_FORMAT_VERSION: u32 = 1;
pub const LOGICAL_PERSONAL_PROFILE_ID: u64 = 0;
pub const LOGICAL_WORK_PROFILE_ID: u64 = 1;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BackupSettings {
    pub grid_name: String,
    pub notification_dots: bool,
    pub themed_icons: bool,
    pub auto_add_apps: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct BackupProfileRef {
    pub profile_id: u64,
    pub kind: ProfileKind,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BackupExportOptions {
    pub include_work: bool,
    pub source_version: String,
    pub source_commit: String,
    pub profiles: Vec<BackupProfileRef>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BackupWidgetMetadata {
    pub item_id: u64,
    pub min_span_x: i32,
    pub min_span_y: i32,
    pub resize_x: i32,
    pub resize_y: i32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BackupDocument {
    pub format_version: u32,
    pub source_version: String,
    pub source_commit: String,
    pub settings: BackupSettings,
    pub profiles: Vec<BackupProfileRef>,
    pub workspace: WorkspaceSnapshot,
    pub widgets: Vec<BackupWidgetMetadata>,
    pub next_item_id: u64,
    pub next_page_id: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ProfileMapping {
    pub source_profile_id: u64,
    pub target_profile_id: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BackupImportTarget {
    pub generation: u64,
    pub expected_generation: u64,
    pub profiles: Vec<BackupProfileRef>,
    pub supported_grid_names: Vec<String>,
    pub applications: Vec<ComponentId>,
    pub shortcuts: Vec<ShortcutId>,
    pub widget_providers: Vec<WidgetProviderId>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BackupImportWarning {
    UnresolvedApplication { item_id: u64 },
    UnresolvedShortcut { item_id: u64 },
    UnresolvedWidget { item_id: u64 },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BackupImportPlan {
    pub workspace: WorkspaceSnapshot,
    pub settings: BackupSettings,
    pub profiles: Vec<BackupProfileRef>,
    pub widgets: Vec<BackupWidgetMetadata>,
    pub next_item_id: u64,
    pub next_page_id: u64,
    pub unresolved_applications: Vec<u64>,
    pub unresolved_shortcuts: Vec<u64>,
    pub unresolved_widgets: Vec<u64>,
    pub warnings: Vec<BackupImportWarning>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BackupError {
    UnsupportedVersion,
    InvalidSourceMetadata,
    PrivateProfile,
    InvalidProfile,
    UnmappedProfile,
    DuplicateMapping,
    DuplicateId,
    DuplicateComponent,
    DuplicateShortcut,
    InvalidSpan,
    InvalidAllocator,
    UnsupportedGrid,
    StaleGeneration,
    Occupied,
    OutOfBounds,
    MissingItem,
    MissingPage,
    MissingFolder,
    Full,
    InvalidGrid,
    InvalidTitle,
    CrossProfile,
    WidgetTooLarge,
    InvariantViolation,
}

impl From<WorkspaceError> for BackupError {
    fn from(error: WorkspaceError) -> Self {
        match error {
            WorkspaceError::Occupied => Self::Occupied,
            WorkspaceError::OutOfBounds => Self::OutOfBounds,
            WorkspaceError::MissingItem => Self::MissingItem,
            WorkspaceError::MissingPage => Self::MissingPage,
            WorkspaceError::MissingFolder => Self::MissingFolder,
            WorkspaceError::InvalidProfile => Self::InvalidProfile,
            WorkspaceError::Full => Self::Full,
            WorkspaceError::StaleGeneration => Self::StaleGeneration,
            WorkspaceError::InvalidGrid => Self::InvalidGrid,
            WorkspaceError::InvalidTitle => Self::InvalidTitle,
            WorkspaceError::CrossProfile => Self::CrossProfile,
            WorkspaceError::WidgetTooLarge => Self::WidgetTooLarge,
            WorkspaceError::InvariantViolation => Self::InvariantViolation,
        }
    }
}

pub fn build_backup_document(
    mut workspace: WorkspaceSnapshot,
    settings: BackupSettings,
    widgets: Vec<BackupWidgetMetadata>,
    options: BackupExportOptions,
    next_item_id: u64,
    next_page_id: u64,
) -> Result<BackupDocument, BackupError> {
    validate_source_meta(&options.source_version)?;
    validate_source_meta(&options.source_commit)?;
    let (map, profiles) = canonical_export_profiles(&options)?;
    reject_mixed_folders(&workspace)?;
    if !options.include_work
        && let Some(work_id) = options
            .profiles
            .iter()
            .find(|profile| profile.kind == ProfileKind::Work)
            .map(|profile| profile.profile_id)
    {
        drop_profile(&mut workspace, work_id)?;
    }
    remap_workspace(&mut workspace, &map)?;
    canonicalize(BackupDocument {
        format_version: BACKUP_FORMAT_VERSION,
        source_version: options.source_version,
        source_commit: options.source_commit,
        settings,
        profiles,
        workspace,
        widgets,
        next_item_id,
        next_page_id,
    })
}

pub fn validate_backup_document(document: BackupDocument) -> Result<(), BackupError> {
    canonicalize(document).map(|_| ())
}

pub fn plan_backup_import(
    document: BackupDocument,
    target: BackupImportTarget,
    mappings: Vec<ProfileMapping>,
) -> Result<BackupImportPlan, BackupError> {
    let mut document = canonicalize(document)?;
    if target.expected_generation != target.generation {
        return Err(BackupError::StaleGeneration);
    }
    if !target
        .supported_grid_names
        .iter()
        .any(|name| name == &document.settings.grid_name)
    {
        return Err(BackupError::UnsupportedGrid);
    }
    validate_live_profiles(&target.profiles)?;
    let map = mapping_table(&document.profiles, &target.profiles, &mappings)?;
    document.workspace.generation = target
        .generation
        .checked_add(1)
        .ok_or(BackupError::InvariantViolation)?;
    remap_workspace(&mut document.workspace, &map)?;
    let profiles = document
        .profiles
        .iter()
        .map(|profile| {
            let profile_id = map[&profile.profile_id];
            target
                .profiles
                .iter()
                .copied()
                .find(|target| target.profile_id == profile_id)
                .ok_or(BackupError::UnmappedProfile)
        })
        .collect::<Result<Vec<_>, _>>()?;
    prepare_snapshot(&mut document.workspace)?;
    let (unresolved_applications, unresolved_shortcuts, unresolved_widgets, warnings) =
        unresolved_identities(&document.workspace, &target);
    Ok(BackupImportPlan {
        workspace: document.workspace,
        settings: document.settings,
        profiles,
        widgets: document.widgets,
        next_item_id: document.next_item_id,
        next_page_id: document.next_page_id,
        unresolved_applications,
        unresolved_shortcuts,
        unresolved_widgets,
        warnings,
    })
}

fn canonicalize(mut document: BackupDocument) -> Result<BackupDocument, BackupError> {
    if document.format_version != BACKUP_FORMAT_VERSION {
        return Err(BackupError::UnsupportedVersion);
    }
    validate_source_meta(&document.source_version)?;
    validate_source_meta(&document.source_commit)?;
    validate_document_bounds(&document)?;
    validate_settings(&document.settings)?;
    if phone_grid(&document.settings.grid_name) != Some(document.workspace.grid) {
        return Err(BackupError::UnsupportedGrid);
    }
    validate_canonical_profiles(&document.profiles)?;
    document.profiles.sort_by_key(|profile| profile.profile_id);
    reject_mixed_folders(&document.workspace)?;
    for folder in &mut document.workspace.folders {
        folder
            .members
            .sort_by_key(|member| (member.rank, member.item_id));
    }
    check_ids_and_cells(&document.workspace)?;
    document.widgets = filter_widget_metadata(&document.workspace, document.widgets)?;
    document.workspace.generation = 0;
    prepare_snapshot(&mut document.workspace)?;
    validate_profile_usage(&document.workspace, &document.profiles)?;
    validate_allocators(&document)?;
    document.widgets.sort_by_key(|widget| widget.item_id);
    Ok(document)
}

fn validate_source_meta(value: &str) -> Result<(), BackupError> {
    if value.is_empty()
        || value.chars().count() > MAX_IDENT_CHARS
        || value.chars().any(char::is_control)
    {
        return Err(BackupError::InvalidSourceMetadata);
    }
    Ok(())
}

fn validate_settings(settings: &BackupSettings) -> Result<(), BackupError> {
    if settings.grid_name.is_empty()
        || settings.grid_name.chars().count() > MAX_IDENT_CHARS
        || settings.grid_name.chars().any(char::is_control)
    {
        return Err(BackupError::InvalidGrid);
    }
    Ok(())
}

fn phone_grid(name: &str) -> Option<GridSpec> {
    let (cols, rows) = match name {
        "2_by_2" => (2, 2),
        "3_by_3" => (3, 3),
        "4_by_4" => (4, 4),
        "4_by_5" => (4, 5),
        "5_by_5" => (5, 5),
        _ => return None,
    };
    Some(GridSpec {
        cols,
        rows,
        hotseat_cols: cols,
    })
}

fn validate_document_bounds(document: &BackupDocument) -> Result<(), BackupError> {
    let members = document
        .workspace
        .folders
        .iter()
        .try_fold(0_usize, |count, folder| {
            count.checked_add(folder.members.len())
        })
        .ok_or(BackupError::InvariantViolation)?;
    let items = document
        .workspace
        .items
        .len()
        .checked_add(members)
        .ok_or(BackupError::InvariantViolation)?;
    if items > MAX_APPLICATIONS
        || document.workspace.pages.len() > 64
        || document.workspace.folders.len() > MAX_APPLICATIONS
        || document.widgets.len() > MAX_APPLICATIONS
        || document.profiles.len() > 2
    {
        return Err(BackupError::InvariantViolation);
    }
    Ok(())
}

fn validate_live_profiles(profiles: &[BackupProfileRef]) -> Result<(), BackupError> {
    let mut ids = HashSet::new();
    let mut personal = 0;
    let mut work = 0;
    for profile in profiles {
        if profile.profile_id > i64::MAX as u64 || !ids.insert(profile.profile_id) {
            return Err(BackupError::InvalidProfile);
        }
        match profile.kind {
            ProfileKind::Personal => personal += 1,
            ProfileKind::Work => work += 1,
            ProfileKind::Private => return Err(BackupError::PrivateProfile),
            ProfileKind::Other => return Err(BackupError::InvalidProfile),
        }
    }
    if personal != 1 || work > 1 {
        return Err(BackupError::InvalidProfile);
    }
    Ok(())
}

fn validate_canonical_profiles(profiles: &[BackupProfileRef]) -> Result<(), BackupError> {
    validate_live_profiles(profiles)?;
    match profiles {
        [personal]
            if personal.profile_id == LOGICAL_PERSONAL_PROFILE_ID
                && personal.kind == ProfileKind::Personal =>
        {
            Ok(())
        }
        [personal, work]
            if personal.profile_id == LOGICAL_PERSONAL_PROFILE_ID
                && personal.kind == ProfileKind::Personal
                && work.profile_id == LOGICAL_WORK_PROFILE_ID
                && work.kind == ProfileKind::Work =>
        {
            Ok(())
        }
        [work, personal]
            if personal.profile_id == LOGICAL_PERSONAL_PROFILE_ID
                && personal.kind == ProfileKind::Personal
                && work.profile_id == LOGICAL_WORK_PROFILE_ID
                && work.kind == ProfileKind::Work =>
        {
            Ok(())
        }
        _ => Err(BackupError::InvalidProfile),
    }
}

fn canonical_export_profiles(
    options: &BackupExportOptions,
) -> Result<(HashMap<u64, u64>, Vec<BackupProfileRef>), BackupError> {
    validate_live_profiles(&options.profiles)?;
    let personal = options
        .profiles
        .iter()
        .find(|profile| profile.kind == ProfileKind::Personal)
        .ok_or(BackupError::InvalidProfile)?;
    let mut map = HashMap::from([(personal.profile_id, LOGICAL_PERSONAL_PROFILE_ID)]);
    let mut profiles = vec![BackupProfileRef {
        profile_id: LOGICAL_PERSONAL_PROFILE_ID,
        kind: ProfileKind::Personal,
    }];
    if options.include_work
        && let Some(work) = options
            .profiles
            .iter()
            .find(|profile| profile.kind == ProfileKind::Work)
    {
        map.insert(work.profile_id, LOGICAL_WORK_PROFILE_ID);
        profiles.push(BackupProfileRef {
            profile_id: LOGICAL_WORK_PROFILE_ID,
            kind: ProfileKind::Work,
        });
    }
    Ok((map, profiles))
}

fn reject_mixed_folders(workspace: &WorkspaceSnapshot) -> Result<(), BackupError> {
    for folder in &workspace.folders {
        let mut profile = None;
        for member in &folder.members {
            let next = member
                .payload
                .profile_id()
                .ok_or(BackupError::InvariantViolation)?;
            match profile {
                None => profile = Some(next),
                Some(existing) if existing != next => return Err(BackupError::CrossProfile),
                Some(_) => {}
            }
        }
    }
    Ok(())
}

fn drop_profile(workspace: &mut WorkspaceSnapshot, profile_id: u64) -> Result<(), BackupError> {
    workspace.items.retain(|item| {
        item.payload
            .profile_id()
            .is_none_or(|item_profile| item_profile != profile_id)
    });
    for folder in &mut workspace.folders {
        folder.members.retain(|member| {
            member
                .payload
                .profile_id()
                .is_none_or(|item_profile| item_profile != profile_id)
        });
    }
    let dissolve: Vec<_> = workspace
        .folders
        .iter()
        .filter(|folder| folder.members.len() < 2)
        .map(|folder| folder.folder_id)
        .collect();
    for folder_id in dissolve {
        let folder = workspace
            .folders
            .iter()
            .find(|folder| folder.folder_id == folder_id)
            .cloned()
            .ok_or(BackupError::MissingFolder)?;
        if folder.members.len() == 1 {
            let member = folder.members[0].clone();
            let item = workspace
                .items
                .iter_mut()
                .find(|item| item.item_id == folder_id)
                .ok_or(BackupError::MissingItem)?;
            item.item_id = member.item_id;
            item.payload = member.payload;
        } else {
            workspace.items.retain(|item| item.item_id != folder_id);
        }
        workspace
            .folders
            .retain(|folder| folder.folder_id != folder_id);
    }
    Ok(())
}

fn validate_profile_usage(
    workspace: &WorkspaceSnapshot,
    profiles: &[BackupProfileRef],
) -> Result<(), BackupError> {
    let allowed: HashSet<_> = profiles.iter().map(|profile| profile.profile_id).collect();
    let mut used = workspace
        .items
        .iter()
        .filter_map(|item| item.payload.profile_id())
        .chain(workspace.folders.iter().flat_map(|folder| {
            folder
                .members
                .iter()
                .filter_map(|member| member.payload.profile_id())
        }));
    if used.any(|profile_id| !allowed.contains(&profile_id)) {
        return Err(BackupError::InvalidProfile);
    }
    Ok(())
}

fn max_item_id(workspace: &WorkspaceSnapshot) -> u64 {
    workspace
        .items
        .iter()
        .map(|item| item.item_id)
        .chain(workspace.folders.iter().map(|folder| folder.folder_id))
        .chain(
            workspace
                .folders
                .iter()
                .flat_map(|folder| folder.members.iter().map(|member| member.item_id)),
        )
        .max()
        .unwrap_or(0)
}

fn validate_allocators(document: &BackupDocument) -> Result<(), BackupError> {
    let max_item = max_item_id(&document.workspace);
    let max_page = document
        .workspace
        .pages
        .iter()
        .map(|page| page.page_id)
        .max()
        .unwrap_or(0);
    if document.next_item_id <= max_item
        || document.next_page_id <= max_page
        || document.next_item_id == 0
        || document.next_page_id == 0
        || document.next_item_id >= i64::MAX as u64
        || document.next_page_id >= i64::MAX as u64
    {
        return Err(BackupError::InvalidAllocator);
    }
    Ok(())
}

fn check_ids_and_cells(workspace: &WorkspaceSnapshot) -> Result<(), BackupError> {
    let mut item_ids = HashSet::new();
    let mut components = HashSet::new();
    let mut shortcuts = HashSet::new();
    for item in &workspace.items {
        if item.item_id == 0 || item.item_id >= i64::MAX as u64 || !item_ids.insert(item.item_id) {
            return Err(BackupError::DuplicateId);
        }
        if item.cell.span_x <= 0 || item.cell.span_y <= 0 {
            return Err(BackupError::InvalidSpan);
        }
        record_payload(&item.payload, &mut components, &mut shortcuts)?;
    }
    for folder in &workspace.folders {
        for member in &folder.members {
            if member.item_id == 0
                || member.item_id >= i64::MAX as u64
                || !item_ids.insert(member.item_id)
            {
                return Err(BackupError::DuplicateId);
            }
            record_payload(&member.payload, &mut components, &mut shortcuts)?;
        }
    }
    for (index, left) in workspace.items.iter().enumerate() {
        if workspace.items[index + 1..]
            .iter()
            .any(|right| left.container == right.container && cells_overlap(left.cell, right.cell))
        {
            return Err(BackupError::Occupied);
        }
    }
    Ok(())
}

fn record_payload(
    payload: &ItemPayload,
    components: &mut HashSet<ComponentId>,
    shortcuts: &mut HashSet<ShortcutId>,
) -> Result<(), BackupError> {
    match payload {
        ItemPayload::Application(component) => {
            if !components.insert(component.clone()) {
                return Err(BackupError::DuplicateComponent);
            }
        }
        ItemPayload::Shortcut(shortcut) => {
            if !shortcuts.insert(shortcut.clone()) {
                return Err(BackupError::DuplicateShortcut);
            }
        }
        ItemPayload::Widget(_) | ItemPayload::Folder => {}
    }
    Ok(())
}

fn cells_overlap(a: CellRect, b: CellRect) -> bool {
    let (ax, ay, aw, ah) = (
        i64::from(a.cell_x),
        i64::from(a.cell_y),
        i64::from(a.span_x),
        i64::from(a.span_y),
    );
    let (bx, by, bw, bh) = (
        i64::from(b.cell_x),
        i64::from(b.cell_y),
        i64::from(b.span_x),
        i64::from(b.span_y),
    );
    ax < bx + bw && bx < ax + aw && ay < by + bh && by < ay + ah
}

fn filter_widget_metadata(
    workspace: &WorkspaceSnapshot,
    mut widgets: Vec<BackupWidgetMetadata>,
) -> Result<Vec<BackupWidgetMetadata>, BackupError> {
    let widget_items: HashMap<_, _> = workspace
        .items
        .iter()
        .filter(|item| matches!(item.payload, ItemPayload::Widget(_)))
        .map(|item| (item.item_id, item))
        .collect();
    widgets.retain(|widget| widget_items.contains_key(&widget.item_id));
    widgets.sort_by_key(|widget| widget.item_id);
    let mut seen = HashSet::new();
    for widget in &widgets {
        if !seen.insert(widget.item_id) {
            return Err(BackupError::DuplicateId);
        }
        let item = widget_items[&widget.item_id];
        if widget.min_span_x <= 0
            || widget.min_span_y <= 0
            || widget.resize_x <= 0
            || widget.resize_y <= 0
            || widget.min_span_x > item.cell.span_x
            || widget.min_span_y > item.cell.span_y
            || widget.resize_x < widget.min_span_x
            || widget.resize_y < widget.min_span_y
        {
            return Err(BackupError::InvalidSpan);
        }
    }
    if seen.len() != widget_items.len() {
        return Err(BackupError::MissingItem);
    }
    Ok(widgets)
}

fn mapping_table(
    source: &[BackupProfileRef],
    target: &[BackupProfileRef],
    mappings: &[ProfileMapping],
) -> Result<HashMap<u64, u64>, BackupError> {
    let source_by_id: HashMap<_, _> = source
        .iter()
        .map(|profile| (profile.profile_id, profile.kind))
        .collect();
    let target_by_id: HashMap<_, _> = target
        .iter()
        .map(|profile| (profile.profile_id, profile.kind))
        .collect();
    let mut map = HashMap::new();
    let mut targets = HashSet::new();
    for mapping in mappings {
        if map
            .insert(mapping.source_profile_id, mapping.target_profile_id)
            .is_some()
            || !targets.insert(mapping.target_profile_id)
        {
            return Err(BackupError::DuplicateMapping);
        }
        let source_kind = source_by_id
            .get(&mapping.source_profile_id)
            .ok_or(BackupError::UnmappedProfile)?;
        let target_kind = target_by_id
            .get(&mapping.target_profile_id)
            .ok_or(BackupError::UnmappedProfile)?;
        if *source_kind != *target_kind {
            return Err(BackupError::InvalidProfile);
        }
    }
    if source
        .iter()
        .any(|profile| !map.contains_key(&profile.profile_id))
    {
        return Err(BackupError::UnmappedProfile);
    }
    Ok(map)
}

fn remap_workspace(
    workspace: &mut WorkspaceSnapshot,
    map: &HashMap<u64, u64>,
) -> Result<(), BackupError> {
    for item in &mut workspace.items {
        remap_payload(&mut item.payload, map)?;
    }
    for folder in &mut workspace.folders {
        for member in &mut folder.members {
            remap_payload(&mut member.payload, map)?;
        }
    }
    Ok(())
}

fn remap_payload(payload: &mut ItemPayload, map: &HashMap<u64, u64>) -> Result<(), BackupError> {
    let profile_id = match payload {
        ItemPayload::Application(component) => &mut component.profile_id,
        ItemPayload::Shortcut(shortcut) => &mut shortcut.profile_id,
        ItemPayload::Widget(provider) => &mut provider.profile_id,
        ItemPayload::Folder => return Ok(()),
    };
    *profile_id = *map.get(profile_id).ok_or(BackupError::UnmappedProfile)?;
    Ok(())
}

fn unresolved_identities(
    workspace: &WorkspaceSnapshot,
    target: &BackupImportTarget,
) -> (Vec<u64>, Vec<u64>, Vec<u64>, Vec<BackupImportWarning>) {
    let applications: HashSet<_> = target.applications.iter().cloned().collect();
    let shortcuts: HashSet<_> = target.shortcuts.iter().cloned().collect();
    let widgets: HashSet<_> = target.widget_providers.iter().cloned().collect();
    let mut unresolved_applications = Vec::new();
    let mut unresolved_shortcuts = Vec::new();
    let mut unresolved_widgets = Vec::new();
    let mut consider = |item_id: u64, payload: &ItemPayload| match payload {
        ItemPayload::Application(component) if !applications.contains(component) => {
            unresolved_applications.push(item_id);
        }
        ItemPayload::Shortcut(shortcut) if !shortcuts.contains(shortcut) => {
            unresolved_shortcuts.push(item_id);
        }
        ItemPayload::Widget(provider) if !widgets.contains(provider) => {
            unresolved_widgets.push(item_id);
        }
        _ => {}
    };
    for item in &workspace.items {
        consider(item.item_id, &item.payload);
    }
    for folder in &workspace.folders {
        for member in &folder.members {
            consider(member.item_id, &member.payload);
        }
    }
    unresolved_applications.sort_unstable();
    unresolved_shortcuts.sort_unstable();
    unresolved_widgets.sort_unstable();
    let mut warnings = Vec::new();
    warnings.extend(
        unresolved_applications
            .iter()
            .map(|item_id| BackupImportWarning::UnresolvedApplication { item_id: *item_id }),
    );
    warnings.extend(
        unresolved_shortcuts
            .iter()
            .map(|item_id| BackupImportWarning::UnresolvedShortcut { item_id: *item_id }),
    );
    warnings.extend(
        unresolved_widgets
            .iter()
            .map(|item_id| BackupImportWarning::UnresolvedWidget { item_id: *item_id }),
    );
    (
        unresolved_applications,
        unresolved_shortcuts,
        unresolved_widgets,
        warnings,
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::workspace::{
        ContainerRef, Folder, FolderMember, GridSpec, WorkspaceItem, WorkspacePage,
    };

    fn grid() -> GridSpec {
        GridSpec {
            cols: 4,
            rows: 5,
            hotseat_cols: 4,
        }
    }

    fn settings() -> BackupSettings {
        BackupSettings {
            grid_name: "4_by_5".into(),
            notification_dots: true,
            themed_icons: false,
            auto_add_apps: true,
        }
    }

    fn live_personal() -> BackupProfileRef {
        BackupProfileRef {
            profile_id: 42,
            kind: ProfileKind::Personal,
        }
    }

    fn live_work() -> BackupProfileRef {
        BackupProfileRef {
            profile_id: 99,
            kind: ProfileKind::Work,
        }
    }

    fn options(include_work: bool, profiles: Vec<BackupProfileRef>) -> BackupExportOptions {
        BackupExportOptions {
            include_work,
            source_version: "1.0.0".into(),
            source_commit: "abc123".into(),
            profiles,
        }
    }

    fn app(name: &str, profile_id: u64) -> ComponentId {
        ComponentId {
            package: name.into(),
            class: "Main".into(),
            profile_id,
        }
    }

    fn item(id: u64, name: &str, x: i32, y: i32) -> WorkspaceItem {
        WorkspaceItem {
            item_id: id,
            payload: ItemPayload::Application(app(name, 42)),
            container: ContainerRef::Workspace { page_id: 10 },
            cell: CellRect::single(x, y),
        }
    }

    fn workspace(items: Vec<WorkspaceItem>, pages: Vec<WorkspacePage>) -> WorkspaceSnapshot {
        WorkspaceSnapshot {
            generation: 9,
            grid: grid(),
            pages,
            items,
            folders: Vec::new(),
        }
    }

    fn default_pages() -> Vec<WorkspacePage> {
        vec![WorkspacePage {
            page_id: 10,
            rank: 0,
        }]
    }

    fn build(
        snapshot: WorkspaceSnapshot,
        widgets: Vec<BackupWidgetMetadata>,
        include_work: bool,
        profiles: Vec<BackupProfileRef>,
        next_item_id: u64,
        next_page_id: u64,
    ) -> Result<BackupDocument, BackupError> {
        build_backup_document(
            snapshot,
            settings(),
            widgets,
            options(include_work, profiles),
            next_item_id,
            next_page_id,
        )
    }

    fn document() -> BackupDocument {
        build(
            workspace(vec![item(1, "a", 0, 0)], default_pages()),
            vec![],
            false,
            vec![live_personal()],
            2,
            11,
        )
        .unwrap()
    }

    fn target(generation: u64, expected: u64) -> BackupImportTarget {
        BackupImportTarget {
            generation,
            expected_generation: expected,
            profiles: vec![BackupProfileRef {
                profile_id: 50,
                kind: ProfileKind::Personal,
            }],
            supported_grid_names: vec!["4_by_5".into()],
            applications: vec![app("a", 50)],
            shortcuts: vec![],
            widget_providers: vec![],
        }
    }

    fn map_personal() -> Vec<ProfileMapping> {
        vec![ProfileMapping {
            source_profile_id: 0,
            target_profile_id: 50,
        }]
    }

    #[test]
    fn source_metadata_is_required_and_bounded() {
        let mut bad = options(false, vec![live_personal()]);
        bad.source_version = String::new();
        assert_eq!(
            build_backup_document(
                workspace(vec![item(1, "a", 0, 0)], default_pages()),
                settings(),
                vec![],
                bad,
                2,
                11,
            ),
            Err(BackupError::InvalidSourceMetadata)
        );
        let mut control = options(false, vec![live_personal()]);
        control.source_commit = "ab\nc".into();
        assert_eq!(
            build_backup_document(
                workspace(vec![item(1, "a", 0, 0)], default_pages()),
                settings(),
                vec![],
                control,
                2,
                11,
            ),
            Err(BackupError::InvalidSourceMetadata)
        );
        let built = document();
        assert_eq!(built.source_version, "1.0.0");
        assert_eq!(built.source_commit, "abc123");
        assert_eq!(built.workspace.generation, 0);
    }

    #[test]
    fn canonical_ids_and_work_opt_in_exclusion() {
        let mut snapshot = workspace(
            vec![
                item(1, "personal", 0, 0),
                WorkspaceItem {
                    item_id: 2,
                    payload: ItemPayload::Application(app("work", 99)),
                    container: ContainerRef::Workspace { page_id: 10 },
                    cell: CellRect::single(1, 0),
                },
            ],
            default_pages(),
        );
        let included = build(
            snapshot.clone(),
            vec![],
            true,
            vec![live_personal(), live_work()],
            3,
            11,
        )
        .unwrap();
        assert_eq!(
            included.profiles,
            vec![
                BackupProfileRef {
                    profile_id: 0,
                    kind: ProfileKind::Personal
                },
                BackupProfileRef {
                    profile_id: 1,
                    kind: ProfileKind::Work
                },
            ]
        );
        let ids: Vec<_> = included
            .workspace
            .items
            .iter()
            .filter_map(|item| item.payload.profile_id())
            .collect();
        assert_eq!(ids, vec![0, 1]);
        snapshot.folders = vec![];
        let excluded = build(
            snapshot,
            vec![],
            false,
            vec![live_personal(), live_work()],
            3,
            11,
        )
        .unwrap();
        assert_eq!(
            excluded.profiles,
            vec![BackupProfileRef {
                profile_id: 0,
                kind: ProfileKind::Personal
            }]
        );
        assert_eq!(excluded.workspace.items.len(), 1);
        assert_eq!(excluded.workspace.items[0].payload.profile_id(), Some(0));
        let again = build(
            workspace(
                vec![
                    item(1, "personal", 0, 0),
                    WorkspaceItem {
                        item_id: 2,
                        payload: ItemPayload::Application(app("work", 99)),
                        container: ContainerRef::Workspace { page_id: 10 },
                        cell: CellRect::single(1, 0),
                    },
                ],
                default_pages(),
            ),
            vec![],
            false,
            vec![live_personal(), live_work()],
            3,
            11,
        )
        .unwrap();
        assert_eq!(excluded, again);
    }

    #[test]
    fn build_is_deterministic_and_normalizes_ranks_and_trailing_pages() {
        let pages = vec![
            WorkspacePage {
                page_id: 12,
                rank: 7,
            },
            WorkspacePage {
                page_id: 10,
                rank: 2,
            },
            WorkspacePage {
                page_id: 13,
                rank: 9,
            },
        ];
        let items = vec![
            item(1, "a", 0, 0),
            WorkspaceItem {
                item_id: 2,
                payload: ItemPayload::Application(app("b", 42)),
                container: ContainerRef::Workspace { page_id: 13 },
                cell: CellRect::single(0, 0),
            },
        ];
        let left = build(
            workspace(items.clone(), pages.clone()),
            vec![],
            false,
            vec![live_personal()],
            3,
            14,
        )
        .unwrap();
        let right = build(
            workspace(items, pages),
            vec![],
            false,
            vec![live_personal()],
            3,
            14,
        )
        .unwrap();
        assert_eq!(left, right);
        assert_eq!(left.format_version, BACKUP_FORMAT_VERSION);
        assert_eq!(
            left.workspace.pages,
            vec![
                WorkspacePage {
                    page_id: 10,
                    rank: 0
                },
                WorkspacePage {
                    page_id: 12,
                    rank: 1
                },
                WorkspacePage {
                    page_id: 13,
                    rank: 2
                },
            ]
        );
        let trailing = build(
            workspace(
                vec![item(1, "a", 0, 0)],
                vec![
                    WorkspacePage {
                        page_id: 10,
                        rank: 0,
                    },
                    WorkspacePage {
                        page_id: 12,
                        rank: 1,
                    },
                ],
            ),
            vec![],
            false,
            vec![live_personal()],
            2,
            13,
        )
        .unwrap();
        assert_eq!(
            trailing.workspace.pages,
            vec![WorkspacePage {
                page_id: 10,
                rank: 0
            }]
        );
        for seed in 1..32_u64 {
            let mut pages = vec![
                WorkspacePage {
                    page_id: 10 + seed,
                    rank: (seed % 5) as i32 + 3,
                },
                WorkspacePage {
                    page_id: seed,
                    rank: (seed % 3) as i32,
                },
            ];
            if seed % 2 == 0 {
                pages.push(WorkspacePage {
                    page_id: 100 + seed,
                    rank: 20,
                });
            }
            let built = build(
                workspace(
                    vec![WorkspaceItem {
                        item_id: seed,
                        payload: ItemPayload::Application(app(&format!("p{seed}"), 42)),
                        container: ContainerRef::Workspace { page_id: seed },
                        cell: CellRect::single(0, 0),
                    }],
                    pages,
                ),
                vec![],
                false,
                vec![live_personal()],
                seed + 1,
                200 + seed,
            )
            .unwrap();
            let again = built.clone();
            assert_eq!(validate_backup_document(again.clone()), Ok(()));
            assert_eq!(
                built
                    .workspace
                    .pages
                    .iter()
                    .map(|page| page.rank)
                    .collect::<Vec<_>>(),
                (0..built.workspace.pages.len() as i32).collect::<Vec<_>>()
            );
            assert_eq!(built, again);
            assert_eq!(built.workspace.generation, 0);
        }
    }

    #[test]
    fn folder_ranks_normalize() {
        let mut snapshot = workspace(
            vec![WorkspaceItem {
                item_id: 3,
                payload: ItemPayload::Folder,
                container: ContainerRef::Workspace { page_id: 10 },
                cell: CellRect::single(0, 0),
            }],
            default_pages(),
        );
        snapshot.folders = vec![Folder {
            folder_id: 3,
            title: "Apps".into(),
            members: vec![
                FolderMember {
                    item_id: 1,
                    payload: ItemPayload::Application(app("a", 42)),
                    rank: 9,
                },
                FolderMember {
                    item_id: 2,
                    payload: ItemPayload::Application(app("b", 42)),
                    rank: 4,
                },
            ],
        }];
        let built = build(snapshot, vec![], false, vec![live_personal()], 4, 11).unwrap();
        assert_eq!(
            built.workspace.folders[0]
                .members
                .iter()
                .map(|member| (member.item_id, member.rank))
                .collect::<Vec<_>>(),
            vec![(2, 0), (1, 1)]
        );
    }

    #[test]
    fn mappings_rewrite_canonical_ids_and_ignore_source_generation() {
        let mut snapshot = workspace(
            vec![
                item(1, "personal", 0, 0),
                WorkspaceItem {
                    item_id: 2,
                    payload: ItemPayload::Application(app("work", 99)),
                    container: ContainerRef::Workspace { page_id: 10 },
                    cell: CellRect::single(1, 0),
                },
            ],
            default_pages(),
        );
        snapshot.items.push(WorkspaceItem {
            item_id: 3,
            payload: ItemPayload::Shortcut(ShortcutId {
                package: "com.example".into(),
                shortcut_id: "s".into(),
                profile_id: 42,
            }),
            container: ContainerRef::Hotseat,
            cell: CellRect::single(0, 0),
        });
        snapshot.items.push(WorkspaceItem {
            item_id: 4,
            payload: ItemPayload::Widget(WidgetProviderId {
                package: "com.example".into(),
                class: "Widget".into(),
                profile_id: 99,
            }),
            container: ContainerRef::Workspace { page_id: 10 },
            cell: CellRect::single(0, 1),
        });
        let widgets = vec![BackupWidgetMetadata {
            item_id: 4,
            min_span_x: 1,
            min_span_y: 1,
            resize_x: 1,
            resize_y: 1,
        }];
        let document = build(
            snapshot,
            widgets.clone(),
            true,
            vec![live_personal(), live_work()],
            5,
            11,
        )
        .unwrap();
        assert_eq!(document.widgets, widgets);
        assert_eq!(document.workspace.generation, 0);
        let import_target = BackupImportTarget {
            generation: 3,
            expected_generation: 3,
            profiles: vec![
                BackupProfileRef {
                    profile_id: 50,
                    kind: ProfileKind::Personal,
                },
                BackupProfileRef {
                    profile_id: 60,
                    kind: ProfileKind::Work,
                },
            ],
            supported_grid_names: vec!["4_by_5".into()],
            applications: vec![app("personal", 50), app("work", 60)],
            shortcuts: vec![ShortcutId {
                package: "com.example".into(),
                shortcut_id: "s".into(),
                profile_id: 50,
            }],
            widget_providers: vec![WidgetProviderId {
                package: "com.example".into(),
                class: "Widget".into(),
                profile_id: 60,
            }],
        };
        let mappings = vec![
            ProfileMapping {
                source_profile_id: 0,
                target_profile_id: 50,
            },
            ProfileMapping {
                source_profile_id: 1,
                target_profile_id: 60,
            },
        ];
        let plan =
            plan_backup_import(document.clone(), import_target.clone(), mappings.clone()).unwrap();
        assert_eq!(plan.workspace.generation, 4);
        let profiles: Vec<_> = plan
            .workspace
            .items
            .iter()
            .filter_map(|item| item.payload.profile_id())
            .collect();
        assert_eq!(profiles, vec![50, 50, 60, 60]);
        assert!(plan.unresolved_applications.is_empty());
        assert_eq!(plan.widgets, widgets);
        let again = plan_backup_import(document, import_target, mappings).unwrap();
        assert_eq!(plan, again);
    }

    #[test]
    fn widget_metadata_and_duplicate_providers() {
        let provider = WidgetProviderId {
            package: "com.example".into(),
            class: "Dup".into(),
            profile_id: 42,
        };
        let snapshot = workspace(
            vec![
                WorkspaceItem {
                    item_id: 1,
                    payload: ItemPayload::Widget(provider.clone()),
                    container: ContainerRef::Workspace { page_id: 10 },
                    cell: CellRect::single(0, 0),
                },
                WorkspaceItem {
                    item_id: 2,
                    payload: ItemPayload::Widget(provider),
                    container: ContainerRef::Workspace { page_id: 10 },
                    cell: CellRect::single(1, 0),
                },
            ],
            default_pages(),
        );
        let widgets = vec![
            BackupWidgetMetadata {
                item_id: 2,
                min_span_x: 1,
                min_span_y: 1,
                resize_x: 1,
                resize_y: 1,
            },
            BackupWidgetMetadata {
                item_id: 1,
                min_span_x: 1,
                min_span_y: 1,
                resize_x: 1,
                resize_y: 1,
            },
        ];
        let built = build(
            snapshot.clone(),
            widgets,
            false,
            vec![live_personal()],
            3,
            11,
        )
        .unwrap();
        assert_eq!(
            built
                .widgets
                .iter()
                .map(|widget| widget.item_id)
                .collect::<Vec<_>>(),
            vec![1, 2]
        );
        assert_eq!(
            build(
                snapshot,
                vec![BackupWidgetMetadata {
                    item_id: 1,
                    min_span_x: 1,
                    min_span_y: 1,
                    resize_x: 1,
                    resize_y: 1,
                }],
                false,
                vec![live_personal()],
                3,
                11,
            ),
            Err(BackupError::MissingItem)
        );
    }

    #[test]
    fn grid_capabilities_and_unresolved_sets() {
        let document = document();
        assert_eq!(
            plan_backup_import(
                document.clone(),
                BackupImportTarget {
                    supported_grid_names: vec!["5_by_5".into()],
                    ..target(3, 3)
                },
                map_personal(),
            ),
            Err(BackupError::UnsupportedGrid)
        );
        let plan = plan_backup_import(
            document,
            BackupImportTarget {
                applications: vec![],
                ..target(3, 3)
            },
            map_personal(),
        )
        .unwrap();
        assert_eq!(plan.unresolved_applications, vec![1]);
        assert_eq!(
            plan.warnings,
            vec![BackupImportWarning::UnresolvedApplication { item_id: 1 }]
        );
        assert_eq!(plan.workspace.generation, 4);
    }

    #[test]
    fn private_and_mixed_profiles_are_rejected() {
        assert_eq!(
            build(
                workspace(vec![item(1, "a", 0, 0)], default_pages()),
                vec![],
                false,
                vec![
                    live_personal(),
                    BackupProfileRef {
                        profile_id: 2,
                        kind: ProfileKind::Private,
                    },
                ],
                2,
                11,
            ),
            Err(BackupError::PrivateProfile)
        );
        let mut snapshot = workspace(
            vec![WorkspaceItem {
                item_id: 3,
                payload: ItemPayload::Folder,
                container: ContainerRef::Workspace { page_id: 10 },
                cell: CellRect::single(0, 0),
            }],
            default_pages(),
        );
        snapshot.folders = vec![Folder {
            folder_id: 3,
            title: "Mix".into(),
            members: vec![
                FolderMember {
                    item_id: 1,
                    payload: ItemPayload::Application(app("a", 42)),
                    rank: 0,
                },
                FolderMember {
                    item_id: 2,
                    payload: ItemPayload::Application(app("b", 99)),
                    rank: 1,
                },
            ],
        }];
        assert_eq!(
            build(
                snapshot,
                vec![],
                true,
                vec![live_personal(), live_work()],
                4,
                11
            ),
            Err(BackupError::CrossProfile)
        );
    }

    #[test]
    fn explicit_duplicate_span_allocator_and_stale_errors() {
        assert_eq!(
            build(
                workspace(
                    vec![item(1, "a", 0, 0), item(1, "b", 1, 0)],
                    default_pages(),
                ),
                vec![],
                false,
                vec![live_personal()],
                2,
                11,
            ),
            Err(BackupError::DuplicateId)
        );
        assert_eq!(
            build(
                workspace(
                    vec![item(1, "a", 0, 0), item(2, "a", 1, 0)],
                    default_pages(),
                ),
                vec![],
                false,
                vec![live_personal()],
                3,
                11,
            ),
            Err(BackupError::DuplicateComponent)
        );
        let shortcut = |id, name| WorkspaceItem {
            item_id: id,
            payload: ItemPayload::Shortcut(ShortcutId {
                package: "com.example".into(),
                shortcut_id: name,
                profile_id: 42,
            }),
            container: ContainerRef::Workspace { page_id: 10 },
            cell: CellRect::single((id - 1) as i32, 0),
        };
        assert_eq!(
            build(
                workspace(
                    vec![shortcut(1, "s".into()), shortcut(2, "s".into())],
                    default_pages(),
                ),
                vec![],
                false,
                vec![live_personal()],
                3,
                11,
            ),
            Err(BackupError::DuplicateShortcut)
        );
        assert_eq!(
            build(
                workspace(
                    vec![item(1, "a", 0, 0), item(2, "b", 0, 0)],
                    default_pages(),
                ),
                vec![],
                false,
                vec![live_personal()],
                3,
                11,
            ),
            Err(BackupError::Occupied)
        );
        let mut span = workspace(vec![item(1, "a", 0, 0)], default_pages());
        span.items[0].payload = ItemPayload::Widget(WidgetProviderId {
            package: "com.example".into(),
            class: "Wide".into(),
            profile_id: 42,
        });
        span.items[0].cell = CellRect {
            cell_x: 0,
            cell_y: 0,
            span_x: 5,
            span_y: 1,
        };
        assert_eq!(
            build(
                span.clone(),
                vec![BackupWidgetMetadata {
                    item_id: 1,
                    min_span_x: 1,
                    min_span_y: 1,
                    resize_x: 1,
                    resize_y: 1,
                }],
                false,
                vec![live_personal()],
                2,
                11,
            ),
            Err(BackupError::OutOfBounds)
        );
        span.items[0].cell.span_x = 0;
        assert_eq!(
            build(span, vec![], false, vec![live_personal()], 2, 11,),
            Err(BackupError::InvalidSpan)
        );
        let mut future = document();
        future.format_version = 2;
        assert_eq!(
            validate_backup_document(future),
            Err(BackupError::UnsupportedVersion)
        );
        assert_eq!(
            plan_backup_import(document(), target(4, 3), map_personal()),
            Err(BackupError::StaleGeneration)
        );
        assert_eq!(
            build(
                workspace(vec![item(5, "a", 0, 0)], default_pages()),
                vec![],
                false,
                vec![live_personal()],
                5,
                11,
            ),
            Err(BackupError::InvalidAllocator)
        );
    }

    #[test]
    fn malformed_documents_fail_without_panicking() {
        let mut missing_page = document();
        missing_page.workspace.items[0].container = ContainerRef::Workspace { page_id: 999 };
        assert_eq!(
            validate_backup_document(missing_page),
            Err(BackupError::MissingPage)
        );

        let mut extreme_cell = document();
        extreme_cell.workspace.items[0].cell.cell_x = i32::MAX;
        assert_eq!(
            validate_backup_document(extreme_cell),
            Err(BackupError::OutOfBounds)
        );

        let mut oversized = document();
        oversized.workspace.pages = (1..=65)
            .map(|page_id| WorkspacePage {
                page_id,
                rank: page_id as i32,
            })
            .collect();
        assert_eq!(
            validate_backup_document(oversized),
            Err(BackupError::InvariantViolation)
        );
    }

    #[test]
    fn artifact_grid_and_component_identifiers_are_bounded() {
        let mut mismatched_grid = document();
        mismatched_grid.settings.grid_name = "5_by_5".into();
        assert_eq!(
            validate_backup_document(mismatched_grid),
            Err(BackupError::UnsupportedGrid)
        );

        let mut invalid_component = document();
        let ItemPayload::Application(component) = &mut invalid_component.workspace.items[0].payload
        else {
            unreachable!()
        };
        component.package = "x".repeat(256);
        assert_eq!(
            validate_backup_document(invalid_component),
            Err(BackupError::InvariantViolation)
        );
    }
}
