use std::collections::{HashMap, HashSet};

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct ComponentId {
    pub package: String,
    pub class: String,
    pub profile_id: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct ShortcutId {
    pub package: String,
    pub shortcut_id: String,
    pub profile_id: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct WidgetProviderId {
    pub package: String,
    pub class: String,
    pub profile_id: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct GridSpec {
    pub cols: i32,
    pub rows: i32,
    pub hotseat_cols: i32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CellRect {
    pub cell_x: i32,
    pub cell_y: i32,
    pub span_x: i32,
    pub span_y: i32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct WidgetMinimumSpan {
    pub item_id: u64,
    pub span_x: i32,
    pub span_y: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub enum ContainerRef {
    Workspace { page_id: u64 },
    Hotseat,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ItemPayload {
    Application(ComponentId),
    Folder,
    Shortcut(ShortcutId),
    Widget(WidgetProviderId),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorkspacePage {
    pub page_id: u64,
    pub rank: i32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorkspaceItem {
    pub item_id: u64,
    pub payload: ItemPayload,
    pub container: ContainerRef,
    pub cell: CellRect,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FolderMember {
    pub item_id: u64,
    pub payload: ItemPayload,
    pub rank: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Folder {
    pub folder_id: u64,
    pub title: String,
    pub members: Vec<FolderMember>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorkspaceSnapshot {
    pub generation: u64,
    pub grid: GridSpec,
    pub pages: Vec<WorkspacePage>,
    pub items: Vec<WorkspaceItem>,
    pub folders: Vec<Folder>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
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

#[derive(Debug, Clone, PartialEq, Eq)]
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

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WorkspaceError {
    Occupied,
    OutOfBounds,
    MissingItem,
    MissingPage,
    MissingFolder,
    InvalidProfile,
    Full,
    StaleGeneration,
    InvalidGrid,
    InvalidTitle,
    CrossProfile,
    WidgetTooLarge,
    InvariantViolation,
}

impl WorkspaceCommand {
    fn expected_generation(&self) -> u64 {
        match self {
            Self::PlaceFromAllApps {
                expected_generation,
                ..
            }
            | Self::PlaceShortcut {
                expected_generation,
                ..
            }
            | Self::PlaceWidget {
                expected_generation,
                ..
            }
            | Self::ResizeWidget {
                expected_generation,
                ..
            }
            | Self::Move {
                expected_generation,
                ..
            }
            | Self::Remove {
                expected_generation,
                ..
            }
            | Self::Dock {
                expected_generation,
                ..
            }
            | Self::Undock {
                expected_generation,
                ..
            }
            | Self::Reorder {
                expected_generation,
                ..
            }
            | Self::CreateFolder {
                expected_generation,
                ..
            }
            | Self::AddFromAllAppsToFolder {
                expected_generation,
                ..
            }
            | Self::AddShortcutToFolder {
                expected_generation,
                ..
            }
            | Self::AddItemToFolder {
                expected_generation,
                ..
            }
            | Self::MoveFolderMember {
                expected_generation,
                ..
            }
            | Self::RemoveItemFromFolder {
                expected_generation,
                ..
            }
            | Self::RenameFolder {
                expected_generation,
                ..
            }
            | Self::AddPage {
                expected_generation,
                ..
            }
            | Self::RemoveEmptyPage {
                expected_generation,
                ..
            }
            | Self::SetGrid {
                expected_generation,
                ..
            }
            | Self::DropMissing {
                expected_generation,
                ..
            }
            | Self::ReconcileShortcuts {
                expected_generation,
                ..
            }
            | Self::RemoveProfiles {
                expected_generation,
                ..
            }
            | Self::Cancelled {
                expected_generation,
                ..
            } => *expected_generation,
        }
    }
}

pub fn apply_workspace_command(
    mut snapshot: WorkspaceSnapshot,
    command: WorkspaceCommand,
) -> Result<WorkspaceTransition, WorkspaceError> {
    if command.expected_generation() != snapshot.generation {
        return Err(WorkspaceError::StaleGeneration);
    }
    validate_snapshot(&snapshot)?;

    let before = snapshot.clone();
    let mut created_page_ids = Vec::new();
    let mut removed_page_ids = Vec::new();
    let trim = !matches!(
        command,
        WorkspaceCommand::AddPage { .. }
            | WorkspaceCommand::RemoveEmptyPage { .. }
            | WorkspaceCommand::SetGrid { .. }
            | WorkspaceCommand::Cancelled { .. }
    );

    match command {
        WorkspaceCommand::PlaceFromAllApps {
            item_id,
            component,
            page_id,
            cell,
            ..
        } => place_from_all_apps(&mut snapshot, item_id, component, page_id, cell)?,
        WorkspaceCommand::PlaceShortcut {
            item_id,
            shortcut,
            container,
            cell,
            ..
        } => place_shortcut(&mut snapshot, item_id, shortcut, container, cell)?,
        WorkspaceCommand::PlaceWidget {
            item_id,
            provider,
            page_id,
            cell,
            ..
        } => place_widget(&mut snapshot, item_id, provider, page_id, cell)?,
        WorkspaceCommand::ResizeWidget { item_id, cell, .. } => {
            resize_widget(&mut snapshot, item_id, cell)?
        }
        WorkspaceCommand::Move {
            item_id,
            container,
            cell,
            ..
        } => move_item(&mut snapshot, item_id, container, cell, false)?,
        WorkspaceCommand::Remove { item_id, .. } => remove_item(&mut snapshot, item_id)?,
        WorkspaceCommand::Dock { item_id, rank, .. } => move_item(
            &mut snapshot,
            item_id,
            ContainerRef::Hotseat,
            CellRect::single(rank, 0),
            false,
        )?,
        WorkspaceCommand::Undock {
            item_id,
            page_id,
            cell,
            ..
        } => move_item(
            &mut snapshot,
            item_id,
            ContainerRef::Workspace { page_id },
            cell,
            false,
        )?,
        WorkspaceCommand::Reorder {
            item_id,
            container,
            cell,
            ..
        } => move_item(&mut snapshot, item_id, container, cell, true)?,
        WorkspaceCommand::CreateFolder {
            folder_id,
            first_item_id,
            second_item_id,
            ..
        } => create_folder(&mut snapshot, folder_id, first_item_id, second_item_id)?,
        WorkspaceCommand::AddFromAllAppsToFolder {
            item_id,
            component,
            folder_id,
            rank,
            ..
        } => add_from_all_apps_to_folder(&mut snapshot, item_id, component, folder_id, rank)?,
        WorkspaceCommand::AddShortcutToFolder {
            item_id,
            shortcut,
            folder_id,
            rank,
            ..
        } => add_shortcut_to_folder(&mut snapshot, item_id, shortcut, folder_id, rank)?,
        WorkspaceCommand::AddItemToFolder {
            item_id,
            folder_id,
            rank,
            ..
        } => add_item_to_folder(&mut snapshot, item_id, folder_id, rank)?,
        WorkspaceCommand::MoveFolderMember {
            folder_id,
            item_id,
            rank,
            ..
        } => move_folder_member(&mut snapshot, folder_id, item_id, rank)?,
        WorkspaceCommand::RemoveItemFromFolder {
            folder_id,
            item_id,
            container,
            cell,
            ..
        } => remove_item_from_folder(&mut snapshot, folder_id, item_id, container, cell)?,
        WorkspaceCommand::RenameFolder {
            folder_id, title, ..
        } => rename_folder(&mut snapshot, folder_id, title)?,
        WorkspaceCommand::AddPage { page_id, .. } => {
            if page_id == 0
                || page_id >= i64::MAX as u64
                || snapshot.pages.iter().any(|page| page.page_id == page_id)
            {
                return Err(WorkspaceError::InvariantViolation);
            }
            snapshot.pages.push(WorkspacePage {
                page_id,
                rank: snapshot.pages.len() as i32,
            });
            created_page_ids.push(page_id);
        }
        WorkspaceCommand::RemoveEmptyPage { page_id, .. } => {
            remove_empty_page(&mut snapshot, page_id)?;
            removed_page_ids.push(page_id);
        }
        WorkspaceCommand::SetGrid {
            grid,
            next_page_id,
            widget_minimum_spans,
            ..
        } => migrate_grid(
            &mut snapshot,
            grid,
            next_page_id,
            widget_minimum_spans,
            &mut created_page_ids,
            &mut removed_page_ids,
        )?,
        WorkspaceCommand::DropMissing {
            live,
            authoritative_profile_ids,
            ..
        } => {
            for component in &live {
                validate_component(component)?;
            }
            let authoritative = validate_profile_ids(authoritative_profile_ids)?;
            let live: HashSet<_> = live.into_iter().collect();
            drop_missing(&mut snapshot, &live, &authoritative)?;
        }
        WorkspaceCommand::ReconcileShortcuts {
            live,
            authoritative_profile_ids,
            ..
        } => {
            for shortcut in &live {
                validate_shortcut(shortcut)?;
            }
            let authoritative = validate_profile_ids(authoritative_profile_ids)?;
            let live: HashSet<_> = live.into_iter().collect();
            reconcile_shortcuts(&mut snapshot, &live, &authoritative)?;
        }
        WorkspaceCommand::RemoveProfiles { profile_ids, .. } => {
            let removed = validate_profile_ids(profile_ids)?;
            remove_profiles(&mut snapshot, &removed)?;
        }
        WorkspaceCommand::Cancelled { .. } => {}
    }

    if trim {
        removed_page_ids.extend(trim_empty_trailing_pages(&mut snapshot));
    }
    normalize_pages(&mut snapshot.pages)?;
    normalize_folders(&mut snapshot.folders);
    validate_snapshot(&snapshot)?;
    sort_snapshot(&mut snapshot);

    let changed_item_ids = changed_items(&before, &snapshot);
    let changed = before.grid != snapshot.grid
        || before.pages != snapshot.pages
        || before.items != snapshot.items
        || before.folders != snapshot.folders;
    if changed {
        snapshot.generation = snapshot
            .generation
            .checked_add(1)
            .ok_or(WorkspaceError::InvariantViolation)?;
    }
    created_page_ids.sort_unstable();
    removed_page_ids.sort_unstable();
    removed_page_ids.dedup();
    Ok(WorkspaceTransition {
        generation: snapshot.generation,
        grid: snapshot.grid,
        pages: snapshot.pages,
        items: snapshot.items,
        folders: snapshot.folders,
        created_page_ids,
        removed_page_ids,
        changed_item_ids,
    })
}

impl CellRect {
    pub const fn single(cell_x: i32, cell_y: i32) -> Self {
        Self {
            cell_x,
            cell_y,
            span_x: 1,
            span_y: 1,
        }
    }
}

fn place_from_all_apps(
    snapshot: &mut WorkspaceSnapshot,
    item_id: u64,
    component: ComponentId,
    page_id: u64,
    cell: CellRect,
) -> Result<(), WorkspaceError> {
    validate_component(&component)?;
    place_payload(
        snapshot,
        item_id,
        ItemPayload::Application(component),
        page_id,
        cell,
    )
}

fn place_shortcut(
    snapshot: &mut WorkspaceSnapshot,
    item_id: u64,
    shortcut: ShortcutId,
    container: ContainerRef,
    cell: CellRect,
) -> Result<(), WorkspaceError> {
    validate_shortcut(&shortcut)?;
    place_payload_in_container(
        snapshot,
        item_id,
        ItemPayload::Shortcut(shortcut),
        container,
        cell,
    )
}

fn place_widget(
    snapshot: &mut WorkspaceSnapshot,
    item_id: u64,
    provider: WidgetProviderId,
    page_id: u64,
    cell: CellRect,
) -> Result<(), WorkspaceError> {
    validate_widget_provider(&provider)?;
    place_payload(
        snapshot,
        item_id,
        ItemPayload::Widget(provider),
        page_id,
        cell,
    )
}

fn resize_widget(
    snapshot: &mut WorkspaceSnapshot,
    item_id: u64,
    cell: CellRect,
) -> Result<(), WorkspaceError> {
    let item = snapshot
        .items
        .iter()
        .find(|item| item.item_id == item_id)
        .ok_or(WorkspaceError::MissingItem)?;
    if !matches!(item.payload, ItemPayload::Widget(_)) {
        return Err(WorkspaceError::InvariantViolation);
    }
    let container = item.container.clone();
    validate_destination(snapshot, &container, cell)?;
    if occupied(snapshot, &container, cell, Some(item_id)) {
        return Err(WorkspaceError::Occupied);
    }
    snapshot
        .items
        .iter_mut()
        .find(|item| item.item_id == item_id)
        .unwrap()
        .cell = cell;
    Ok(())
}

fn place_payload(
    snapshot: &mut WorkspaceSnapshot,
    item_id: u64,
    payload: ItemPayload,
    page_id: u64,
    cell: CellRect,
) -> Result<(), WorkspaceError> {
    place_payload_in_container(
        snapshot,
        item_id,
        payload,
        ContainerRef::Workspace { page_id },
        cell,
    )
}

fn place_payload_in_container(
    snapshot: &mut WorkspaceSnapshot,
    item_id: u64,
    payload: ItemPayload,
    container: ContainerRef,
    cell: CellRect,
) -> Result<(), WorkspaceError> {
    if item_id == 0
        || item_id >= i64::MAX as u64
        || snapshot.items.iter().any(|item| item.item_id == item_id)
        || payload_exists(snapshot, &payload)
    {
        return Err(WorkspaceError::InvariantViolation);
    }
    validate_destination(snapshot, &container, cell)?;
    if occupied(snapshot, &container, cell, None) {
        return Err(WorkspaceError::Occupied);
    }
    snapshot.items.push(WorkspaceItem {
        item_id,
        payload,
        container,
        cell,
    });
    Ok(())
}

fn move_item(
    snapshot: &mut WorkspaceSnapshot,
    item_id: u64,
    container: ContainerRef,
    cell: CellRect,
    reorder: bool,
) -> Result<(), WorkspaceError> {
    let index = snapshot
        .items
        .iter()
        .position(|item| item.item_id == item_id)
        .ok_or(WorkspaceError::MissingItem)?;
    if matches!(snapshot.items[index].payload, ItemPayload::Widget(_))
        && matches!(container, ContainerRef::Hotseat)
    {
        return Err(WorkspaceError::InvariantViolation);
    }
    validate_destination(snapshot, &container, cell)?;
    if snapshot.items[index].container == container && snapshot.items[index].cell == cell {
        return Ok(());
    }

    let occupant = snapshot.items.iter().position(|item| {
        item.item_id != item_id && item.container == container && overlaps(item.cell, cell)
    });
    if let Some(occupant) = occupant {
        if !reorder
            || cell.span_x != 1
            || cell.span_y != 1
            || snapshot.items[occupant].cell.span_x != 1
            || snapshot.items[occupant].cell.span_y != 1
        {
            return Err(WorkspaceError::Occupied);
        }
        let vacancy =
            nearest_vacancy(snapshot, &container, cell, item_id).ok_or(WorkspaceError::Full)?;
        snapshot.items[occupant].cell = vacancy;
    }
    snapshot.items[index].container = container;
    snapshot.items[index].cell = cell;
    Ok(())
}

fn remove_item(snapshot: &mut WorkspaceSnapshot, item_id: u64) -> Result<(), WorkspaceError> {
    let item = snapshot
        .items
        .iter()
        .find(|item| item.item_id == item_id)
        .cloned();
    if item.is_none() {
        let folder_id = snapshot
            .folders
            .iter()
            .find(|folder| {
                folder
                    .members
                    .iter()
                    .any(|member| member.item_id == item_id)
            })
            .map(|folder| folder.folder_id)
            .ok_or(WorkspaceError::MissingItem)?;
        let folder = snapshot
            .folders
            .iter_mut()
            .find(|folder| folder.folder_id == folder_id)
            .expect("folder exists");
        folder.members.retain(|member| member.item_id != item_id);
        normalize_members(&mut folder.members);
        return dissolve_if_needed(snapshot, folder_id);
    }
    let item = item.expect("checked above");
    snapshot.items.retain(|item| item.item_id != item_id);
    if item.payload == ItemPayload::Folder {
        snapshot
            .folders
            .retain(|folder| folder.folder_id != item_id);
    }
    Ok(())
}

fn create_folder(
    snapshot: &mut WorkspaceSnapshot,
    folder_id: u64,
    first_item_id: u64,
    second_item_id: u64,
) -> Result<(), WorkspaceError> {
    if folder_id == 0
        || folder_id >= i64::MAX as u64
        || first_item_id == second_item_id
        || id_exists(snapshot, folder_id)
    {
        return Err(WorkspaceError::InvariantViolation);
    }
    let first = snapshot
        .items
        .iter()
        .find(|item| item.item_id == first_item_id)
        .cloned()
        .ok_or(WorkspaceError::MissingItem)?;
    let second = snapshot
        .items
        .iter()
        .find(|item| item.item_id == second_item_id)
        .cloned()
        .ok_or(WorkspaceError::MissingItem)?;
    if !matches!(
        first.payload,
        ItemPayload::Application(_) | ItemPayload::Shortcut(_)
    ) || !matches!(
        second.payload,
        ItemPayload::Application(_) | ItemPayload::Shortcut(_)
    ) {
        return Err(WorkspaceError::InvariantViolation);
    }
    if payload_profile(&first.payload)? != payload_profile(&second.payload)? {
        return Err(WorkspaceError::CrossProfile);
    }

    snapshot
        .items
        .retain(|item| item.item_id != first_item_id && item.item_id != second_item_id);
    snapshot.items.push(WorkspaceItem {
        item_id: folder_id,
        payload: ItemPayload::Folder,
        container: second.container,
        cell: second.cell,
    });
    snapshot.folders.push(Folder {
        folder_id,
        title: String::new(),
        members: vec![
            FolderMember {
                item_id: second_item_id,
                payload: second.payload,
                rank: 0,
            },
            FolderMember {
                item_id: first_item_id,
                payload: first.payload,
                rank: 1,
            },
        ],
    });
    Ok(())
}

fn add_from_all_apps_to_folder(
    snapshot: &mut WorkspaceSnapshot,
    item_id: u64,
    component: ComponentId,
    folder_id: u64,
    rank: u32,
) -> Result<(), WorkspaceError> {
    validate_component(&component)?;
    if item_id == 0
        || item_id >= i64::MAX as u64
        || id_exists(snapshot, item_id)
        || component_exists(snapshot, &component)
    {
        return Err(WorkspaceError::InvariantViolation);
    }
    insert_folder_member(
        snapshot,
        folder_id,
        FolderMember {
            item_id,
            payload: ItemPayload::Application(component),
            rank,
        },
        rank,
    )
}

fn add_shortcut_to_folder(
    snapshot: &mut WorkspaceSnapshot,
    item_id: u64,
    shortcut: ShortcutId,
    folder_id: u64,
    rank: u32,
) -> Result<(), WorkspaceError> {
    validate_shortcut(&shortcut)?;
    if item_id == 0
        || item_id >= i64::MAX as u64
        || id_exists(snapshot, item_id)
        || shortcut_exists(snapshot, &shortcut)
    {
        return Err(WorkspaceError::InvariantViolation);
    }
    insert_folder_member(
        snapshot,
        folder_id,
        FolderMember {
            item_id,
            payload: ItemPayload::Shortcut(shortcut),
            rank,
        },
        rank,
    )
}

fn add_item_to_folder(
    snapshot: &mut WorkspaceSnapshot,
    item_id: u64,
    folder_id: u64,
    rank: u32,
) -> Result<(), WorkspaceError> {
    let destination = snapshot
        .folders
        .iter()
        .find(|folder| folder.folder_id == folder_id)
        .ok_or(WorkspaceError::MissingFolder)?;
    if destination
        .members
        .iter()
        .any(|member| member.item_id == item_id)
    {
        return move_folder_member(snapshot, folder_id, item_id, rank);
    }
    let destination_profile = payload_profile(&destination.members[0].payload)?;

    let (payload, source_folder) = if let Some(item) = snapshot
        .items
        .iter()
        .find(|item| item.item_id == item_id)
        .cloned()
    {
        if !matches!(
            item.payload,
            ItemPayload::Application(_) | ItemPayload::Shortcut(_)
        ) {
            return Err(WorkspaceError::InvariantViolation);
        }
        snapshot.items.retain(|item| item.item_id != item_id);
        (item.payload, None)
    } else {
        let source_id = snapshot
            .folders
            .iter()
            .find(|folder| {
                folder
                    .members
                    .iter()
                    .any(|member| member.item_id == item_id)
            })
            .map(|folder| folder.folder_id)
            .ok_or(WorkspaceError::MissingItem)?;
        let source = snapshot
            .folders
            .iter_mut()
            .find(|folder| folder.folder_id == source_id)
            .expect("source folder exists");
        let member = source
            .members
            .iter()
            .find(|member| member.item_id == item_id)
            .cloned()
            .expect("source member exists");
        source.members.retain(|member| member.item_id != item_id);
        normalize_members(&mut source.members);
        (member.payload, Some(source_id))
    };
    if payload_profile(&payload)? != destination_profile {
        return Err(WorkspaceError::CrossProfile);
    }
    if let Some(source_id) = source_folder {
        dissolve_if_needed(snapshot, source_id)?;
    }
    insert_folder_member(
        snapshot,
        folder_id,
        FolderMember {
            item_id,
            payload,
            rank,
        },
        rank,
    )
}

fn insert_folder_member(
    snapshot: &mut WorkspaceSnapshot,
    folder_id: u64,
    mut member: FolderMember,
    rank: u32,
) -> Result<(), WorkspaceError> {
    let folder = snapshot
        .folders
        .iter_mut()
        .find(|folder| folder.folder_id == folder_id)
        .ok_or(WorkspaceError::MissingFolder)?;
    let profile = payload_profile(
        &folder
            .members
            .first()
            .ok_or(WorkspaceError::InvariantViolation)?
            .payload,
    )?;
    if payload_profile(&member.payload)? != profile
        || !matches!(
            member.payload,
            ItemPayload::Application(_) | ItemPayload::Shortcut(_)
        )
    {
        return Err(WorkspaceError::CrossProfile);
    }
    let index = usize::try_from(rank)
        .unwrap_or(usize::MAX)
        .min(folder.members.len());
    member.rank = index as u32;
    folder.members.insert(index, member);
    normalize_members(&mut folder.members);
    Ok(())
}

fn move_folder_member(
    snapshot: &mut WorkspaceSnapshot,
    folder_id: u64,
    item_id: u64,
    rank: u32,
) -> Result<(), WorkspaceError> {
    let folder = snapshot
        .folders
        .iter_mut()
        .find(|folder| folder.folder_id == folder_id)
        .ok_or(WorkspaceError::MissingFolder)?;
    let current = folder
        .members
        .iter()
        .position(|member| member.item_id == item_id)
        .ok_or(WorkspaceError::MissingItem)?;
    let destination = usize::try_from(rank)
        .unwrap_or(usize::MAX)
        .min(folder.members.len() - 1);
    if current == destination {
        return Ok(());
    }
    let member = folder.members.remove(current);
    folder.members.insert(destination, member);
    normalize_members(&mut folder.members);
    Ok(())
}

fn remove_item_from_folder(
    snapshot: &mut WorkspaceSnapshot,
    folder_id: u64,
    item_id: u64,
    container: ContainerRef,
    cell: CellRect,
) -> Result<(), WorkspaceError> {
    validate_destination(snapshot, &container, cell)?;
    if occupied(snapshot, &container, cell, None) {
        return Err(WorkspaceError::Occupied);
    }
    let folder = snapshot
        .folders
        .iter_mut()
        .find(|folder| folder.folder_id == folder_id)
        .ok_or(WorkspaceError::MissingFolder)?;
    let member = folder
        .members
        .iter()
        .find(|member| member.item_id == item_id)
        .cloned()
        .ok_or(WorkspaceError::MissingItem)?;
    folder.members.retain(|member| member.item_id != item_id);
    normalize_members(&mut folder.members);
    snapshot.items.push(WorkspaceItem {
        item_id,
        payload: member.payload,
        container,
        cell,
    });
    dissolve_if_needed(snapshot, folder_id)
}

fn rename_folder(
    snapshot: &mut WorkspaceSnapshot,
    folder_id: u64,
    title: String,
) -> Result<(), WorkspaceError> {
    validate_title(&title)?;
    let folder = snapshot
        .folders
        .iter_mut()
        .find(|folder| folder.folder_id == folder_id)
        .ok_or(WorkspaceError::MissingFolder)?;
    folder.title = title;
    Ok(())
}

fn dissolve_if_needed(
    snapshot: &mut WorkspaceSnapshot,
    folder_id: u64,
) -> Result<(), WorkspaceError> {
    let folder = snapshot
        .folders
        .iter()
        .find(|folder| folder.folder_id == folder_id)
        .cloned()
        .ok_or(WorkspaceError::MissingFolder)?;
    if folder.members.len() > 1 {
        return Ok(());
    }
    let placement = snapshot
        .items
        .iter()
        .find(|item| item.item_id == folder_id && item.payload == ItemPayload::Folder)
        .cloned()
        .ok_or(WorkspaceError::InvariantViolation)?;
    snapshot.items.retain(|item| item.item_id != folder_id);
    snapshot
        .folders
        .retain(|folder| folder.folder_id != folder_id);
    if let Some(member) = folder.members.into_iter().next() {
        snapshot.items.push(WorkspaceItem {
            item_id: member.item_id,
            payload: member.payload,
            container: placement.container,
            cell: placement.cell,
        });
    }
    Ok(())
}

fn drop_missing(
    snapshot: &mut WorkspaceSnapshot,
    live: &HashSet<ComponentId>,
    authoritative: &HashSet<u64>,
) -> Result<(), WorkspaceError> {
    snapshot.items.retain(|item| match &item.payload {
        ItemPayload::Application(component) => {
            !authoritative.contains(&component.profile_id) || live.contains(component)
        }
        ItemPayload::Folder | ItemPayload::Shortcut(_) | ItemPayload::Widget(_) => true,
    });
    for folder in &mut snapshot.folders {
        folder.members.retain(|member| match &member.payload {
            ItemPayload::Application(component) => {
                !authoritative.contains(&component.profile_id) || live.contains(component)
            }
            ItemPayload::Shortcut(_) => true,
            ItemPayload::Folder | ItemPayload::Widget(_) => false,
        });
        normalize_members(&mut folder.members);
    }
    let dissolve: Vec<_> = snapshot
        .folders
        .iter()
        .filter(|folder| folder.members.len() <= 1)
        .map(|folder| folder.folder_id)
        .collect();
    for folder_id in dissolve {
        dissolve_if_needed(snapshot, folder_id)?;
    }
    Ok(())
}

fn reconcile_shortcuts(
    snapshot: &mut WorkspaceSnapshot,
    live: &HashSet<ShortcutId>,
    authoritative: &HashSet<u64>,
) -> Result<(), WorkspaceError> {
    snapshot.items.retain(|item| match &item.payload {
        ItemPayload::Shortcut(shortcut) => {
            !authoritative.contains(&shortcut.profile_id) || live.contains(shortcut)
        }
        ItemPayload::Application(_) | ItemPayload::Folder | ItemPayload::Widget(_) => true,
    });
    for folder in &mut snapshot.folders {
        folder.members.retain(|member| match &member.payload {
            ItemPayload::Shortcut(shortcut) => {
                !authoritative.contains(&shortcut.profile_id) || live.contains(shortcut)
            }
            ItemPayload::Application(_) => true,
            ItemPayload::Folder | ItemPayload::Widget(_) => false,
        });
        normalize_members(&mut folder.members);
    }
    let dissolve: Vec<_> = snapshot
        .folders
        .iter()
        .filter(|folder| folder.members.len() <= 1)
        .map(|folder| folder.folder_id)
        .collect();
    for folder_id in dissolve {
        dissolve_if_needed(snapshot, folder_id)?;
    }
    Ok(())
}

fn remove_profiles(
    snapshot: &mut WorkspaceSnapshot,
    removed: &HashSet<u64>,
) -> Result<(), WorkspaceError> {
    snapshot.items.retain(|item| {
        item.payload
            .profile_id()
            .is_none_or(|profile_id| !removed.contains(&profile_id))
    });
    for folder in &mut snapshot.folders {
        folder.members.retain(|member| {
            member
                .payload
                .profile_id()
                .is_none_or(|profile_id| !removed.contains(&profile_id))
        });
        normalize_members(&mut folder.members);
    }
    let dissolve: Vec<_> = snapshot
        .folders
        .iter()
        .filter(|folder| folder.members.len() <= 1)
        .map(|folder| folder.folder_id)
        .collect();
    for folder_id in dissolve {
        dissolve_if_needed(snapshot, folder_id)?;
    }
    Ok(())
}

impl ItemPayload {
    pub(crate) fn profile_id(&self) -> Option<u64> {
        match self {
            Self::Application(component) => Some(component.profile_id),
            Self::Shortcut(shortcut) => Some(shortcut.profile_id),
            Self::Widget(provider) => Some(provider.profile_id),
            Self::Folder => None,
        }
    }
}

fn validate_profile_ids(profile_ids: Vec<u64>) -> Result<HashSet<u64>, WorkspaceError> {
    if profile_ids.len() > crate::MAX_VISIBLE_PROFILES
        || profile_ids
            .iter()
            .any(|profile_id| *profile_id > i64::MAX as u64)
    {
        return Err(WorkspaceError::InvalidProfile);
    }
    Ok(profile_ids.into_iter().collect())
}

fn remove_empty_page(snapshot: &mut WorkspaceSnapshot, page_id: u64) -> Result<(), WorkspaceError> {
    if snapshot.pages.len() == 1 {
        return Err(WorkspaceError::MissingPage);
    }
    let page = snapshot
        .pages
        .iter()
        .find(|page| page.page_id == page_id)
        .ok_or(WorkspaceError::MissingPage)?;
    if page.rank != snapshot.pages.len() as i32 - 1
        || snapshot.items.iter().any(
            |item| matches!(item.container, ContainerRef::Workspace { page_id: id } if id == page_id),
        )
    {
        return Err(WorkspaceError::InvariantViolation);
    }
    snapshot.pages.retain(|page| page.page_id != page_id);
    Ok(())
}

fn trim_empty_trailing_pages(snapshot: &mut WorkspaceSnapshot) -> Vec<u64> {
    let mut removed = Vec::new();
    while snapshot.pages.len() > 1 {
        let last = snapshot.pages.last().expect("nonempty pages").page_id;
        if snapshot.items.iter().any(
            |item| matches!(item.container, ContainerRef::Workspace { page_id } if page_id == last),
        ) {
            break;
        }
        snapshot.pages.pop();
        removed.push(last);
    }
    removed
}

fn migrate_grid(
    snapshot: &mut WorkspaceSnapshot,
    grid: GridSpec,
    mut next_page_id: u64,
    widget_minimum_spans: Vec<WidgetMinimumSpan>,
    created_page_ids: &mut Vec<u64>,
    removed_page_ids: &mut Vec<u64>,
) -> Result<(), WorkspaceError> {
    validate_grid(grid)?;
    if next_page_id == 0 || next_page_id >= i64::MAX as u64 {
        return Err(WorkspaceError::InvariantViolation);
    }

    let mut minimums = HashMap::new();
    for minimum in widget_minimum_spans {
        if minimum.item_id == 0
            || minimum.span_x <= 0
            || minimum.span_y <= 0
            || minimums
                .insert(minimum.item_id, (minimum.span_x, minimum.span_y))
                .is_some()
        {
            return Err(WorkspaceError::InvariantViolation);
        }
        let item = snapshot
            .items
            .iter()
            .find(|item| item.item_id == minimum.item_id)
            .ok_or(WorkspaceError::MissingItem)?;
        if !matches!(item.payload, ItemPayload::Widget(_)) {
            return Err(WorkspaceError::InvariantViolation);
        }
    }

    let page_ranks: HashMap<_, _> = snapshot
        .pages
        .iter()
        .map(|page| (page.page_id, page.rank))
        .collect();
    let mut ordered = snapshot.items.clone();
    ordered.sort_by_key(|item| match item.container {
        ContainerRef::Hotseat => (0, 0, item.cell.cell_x, item.cell.cell_y, item.item_id),
        ContainerRef::Workspace { page_id } => (
            1,
            *page_ranks.get(&page_id).unwrap_or(&i32::MAX),
            item.cell.cell_y,
            item.cell.cell_x,
            item.item_id,
        ),
    });

    snapshot.grid = grid;
    snapshot.items.clear();
    let mut pending = Vec::new();
    for mut item in ordered {
        if matches!(item.payload, ItemPayload::Widget(_)) {
            let minimum = minimums
                .get(&item.item_id)
                .copied()
                .unwrap_or((item.cell.span_x, item.cell.span_y));
            if minimum.0 > grid.cols || minimum.1 > grid.rows {
                return Err(WorkspaceError::WidgetTooLarge);
            }
            item.cell.span_x = item.cell.span_x.min(grid.cols).max(minimum.0);
            item.cell.span_y = item.cell.span_y.min(grid.rows).max(minimum.1);
        }
        let fits = validate_destination(snapshot, &item.container, item.cell).is_ok()
            && !occupied(snapshot, &item.container, item.cell, None);
        if fits {
            snapshot.items.push(item);
        } else {
            pending.push(item);
        }
    }

    for mut item in pending {
        let start_rank = match item.container {
            ContainerRef::Workspace { page_id } => *page_ranks.get(&page_id).unwrap_or(&0),
            ContainerRef::Hotseat => 0,
        };
        let span = item.cell;
        let mut destination = snapshot
            .pages
            .iter()
            .skip(start_rank.max(0) as usize)
            .find_map(|page| {
                let container = ContainerRef::Workspace {
                    page_id: page.page_id,
                };
                first_vacancy(snapshot, &container, span.span_x, span.span_y)
                    .map(|cell| (container, cell))
            });
        if destination.is_none() {
            while snapshot
                .pages
                .iter()
                .any(|page| page.page_id == next_page_id)
            {
                next_page_id = next_page_id
                    .checked_add(1)
                    .filter(|id| *id < i64::MAX as u64)
                    .ok_or(WorkspaceError::InvariantViolation)?;
            }
            let page_id = next_page_id;
            next_page_id = next_page_id
                .checked_add(1)
                .filter(|id| *id < i64::MAX as u64)
                .ok_or(WorkspaceError::InvariantViolation)?;
            snapshot.pages.push(WorkspacePage {
                page_id,
                rank: snapshot.pages.len() as i32,
            });
            created_page_ids.push(page_id);
            let container = ContainerRef::Workspace { page_id };
            destination = first_vacancy(snapshot, &container, span.span_x, span.span_y)
                .map(|cell| (container, cell));
        }
        let (container, cell) = destination.ok_or(WorkspaceError::Full)?;
        item.container = container;
        item.cell = cell;
        snapshot.items.push(item);
    }

    removed_page_ids.extend(trim_empty_trailing_pages(snapshot));
    Ok(())
}

fn first_vacancy(
    snapshot: &WorkspaceSnapshot,
    container: &ContainerRef,
    span_x: i32,
    span_y: i32,
) -> Option<CellRect> {
    let (cols, rows) = dimensions(snapshot.grid, container);
    for y in 0..=rows - span_y {
        for x in 0..=cols - span_x {
            let cell = CellRect {
                cell_x: x,
                cell_y: y,
                span_x,
                span_y,
            };
            if !occupied(snapshot, container, cell, None) {
                return Some(cell);
            }
        }
    }
    None
}

// Nearest means Manhattan distance, then visual row, column, and finally item-independent order.
fn nearest_vacancy(
    snapshot: &WorkspaceSnapshot,
    container: &ContainerRef,
    requested: CellRect,
    moving_id: u64,
) -> Option<CellRect> {
    let (cols, rows) = dimensions(snapshot.grid, container);
    let mut vacancies = Vec::new();
    for y in 0..rows {
        for x in 0..cols {
            let candidate = CellRect::single(x, y);
            if !occupied(snapshot, container, candidate, Some(moving_id)) {
                vacancies.push(candidate);
            }
        }
    }
    vacancies.sort_by_key(|candidate| {
        (
            (candidate.cell_x - requested.cell_x).abs()
                + (candidate.cell_y - requested.cell_y).abs(),
            candidate.cell_y,
            candidate.cell_x,
        )
    });
    vacancies.into_iter().next()
}

fn occupied(
    snapshot: &WorkspaceSnapshot,
    container: &ContainerRef,
    cell: CellRect,
    except: Option<u64>,
) -> bool {
    snapshot.items.iter().any(|item| {
        Some(item.item_id) != except && item.container == *container && overlaps(item.cell, cell)
    })
}

fn overlaps(a: CellRect, b: CellRect) -> bool {
    a.cell_x < b.cell_x + b.span_x
        && b.cell_x < a.cell_x + a.span_x
        && a.cell_y < b.cell_y + b.span_y
        && b.cell_y < a.cell_y + a.span_y
}

fn dimensions(grid: GridSpec, container: &ContainerRef) -> (i32, i32) {
    match container {
        ContainerRef::Workspace { .. } => (grid.cols, grid.rows),
        ContainerRef::Hotseat => (grid.hotseat_cols, 1),
    }
}

fn validate_destination(
    snapshot: &WorkspaceSnapshot,
    container: &ContainerRef,
    cell: CellRect,
) -> Result<(), WorkspaceError> {
    if let ContainerRef::Workspace { page_id } = container
        && !snapshot.pages.iter().any(|page| page.page_id == *page_id)
    {
        return Err(WorkspaceError::MissingPage);
    }
    let (cols, rows) = dimensions(snapshot.grid, container);
    if cell.cell_x < 0
        || cell.cell_y < 0
        || cell.span_x <= 0
        || cell.span_y <= 0
        || cell.cell_x + cell.span_x > cols
        || cell.cell_y + cell.span_y > rows
    {
        return Err(WorkspaceError::OutOfBounds);
    }
    Ok(())
}

fn validate_component(component: &ComponentId) -> Result<(), WorkspaceError> {
    if component.profile_id > i64::MAX as u64 {
        return Err(WorkspaceError::InvalidProfile);
    }
    if component.package.is_empty() || component.class.is_empty() {
        return Err(WorkspaceError::InvariantViolation);
    }
    Ok(())
}

fn validate_shortcut(shortcut: &ShortcutId) -> Result<(), WorkspaceError> {
    if shortcut.profile_id > i64::MAX as u64 {
        return Err(WorkspaceError::InvalidProfile);
    }
    if shortcut.package.is_empty()
        || shortcut.shortcut_id.is_empty()
        || shortcut.package.chars().count() > 255
        || shortcut.shortcut_id.chars().count() > 100
        || shortcut.package.chars().any(char::is_control)
        || shortcut.shortcut_id.chars().any(char::is_control)
    {
        return Err(WorkspaceError::InvariantViolation);
    }
    Ok(())
}

fn validate_widget_provider(provider: &WidgetProviderId) -> Result<(), WorkspaceError> {
    if provider.profile_id > i64::MAX as u64 {
        return Err(WorkspaceError::InvalidProfile);
    }
    if provider.package.is_empty()
        || provider.class.is_empty()
        || provider.package.chars().count() > 255
        || provider.class.chars().count() > 255
        || provider.package.chars().any(char::is_control)
        || provider.class.chars().any(char::is_control)
    {
        return Err(WorkspaceError::InvariantViolation);
    }
    Ok(())
}

fn payload_profile(payload: &ItemPayload) -> Result<u64, WorkspaceError> {
    match payload {
        ItemPayload::Application(component) => Ok(component.profile_id),
        ItemPayload::Shortcut(shortcut) => Ok(shortcut.profile_id),
        ItemPayload::Widget(provider) => Ok(provider.profile_id),
        ItemPayload::Folder => Err(WorkspaceError::InvariantViolation),
    }
}

fn validate_title(title: &str) -> Result<(), WorkspaceError> {
    if title.chars().count() > 80 || title.chars().any(char::is_control) {
        return Err(WorkspaceError::InvalidTitle);
    }
    Ok(())
}

fn id_exists(snapshot: &WorkspaceSnapshot, item_id: u64) -> bool {
    snapshot.items.iter().any(|item| item.item_id == item_id)
        || snapshot.folders.iter().any(|folder| {
            folder.folder_id == item_id
                || folder
                    .members
                    .iter()
                    .any(|member| member.item_id == item_id)
        })
}

fn component_exists(snapshot: &WorkspaceSnapshot, component: &ComponentId) -> bool {
    snapshot.items.iter().any(
        |item| matches!(&item.payload, ItemPayload::Application(existing) if existing == component),
    ) || snapshot.folders.iter().any(|folder| {
        folder
            .members
            .iter()
            .any(|member| matches!(&member.payload, ItemPayload::Application(existing) if existing == component))
    })
}

fn shortcut_exists(snapshot: &WorkspaceSnapshot, shortcut: &ShortcutId) -> bool {
    snapshot.items.iter().any(
        |item| matches!(&item.payload, ItemPayload::Shortcut(existing) if existing == shortcut),
    ) || snapshot.folders.iter().any(|folder| {
        folder
            .members
            .iter()
            .any(|member| matches!(&member.payload, ItemPayload::Shortcut(existing) if existing == shortcut))
    })
}

fn payload_exists(snapshot: &WorkspaceSnapshot, payload: &ItemPayload) -> bool {
    match payload {
        ItemPayload::Application(component) => component_exists(snapshot, component),
        ItemPayload::Shortcut(shortcut) => shortcut_exists(snapshot, shortcut),
        ItemPayload::Widget(_) => false,
        ItemPayload::Folder => true,
    }
}

fn validate_snapshot(snapshot: &WorkspaceSnapshot) -> Result<(), WorkspaceError> {
    validate_grid(snapshot.grid)?;
    if snapshot.pages.is_empty() {
        return Err(WorkspaceError::MissingPage);
    }
    let page_ids: HashSet<_> = snapshot.pages.iter().map(|page| page.page_id).collect();
    if page_ids.len() != snapshot.pages.len()
        || page_ids.contains(&0)
        || page_ids.iter().any(|id| *id >= i64::MAX as u64)
    {
        return Err(WorkspaceError::InvariantViolation);
    }
    if snapshot
        .pages
        .iter()
        .enumerate()
        .any(|(rank, page)| page.rank != rank as i32)
    {
        return Err(WorkspaceError::InvariantViolation);
    }

    let mut item_ids = HashSet::new();
    let mut components = HashSet::new();
    let mut shortcuts = HashSet::new();
    let folder_ids: HashSet<_> = snapshot
        .folders
        .iter()
        .map(|folder| folder.folder_id)
        .collect();
    if folder_ids.len() != snapshot.folders.len() {
        return Err(WorkspaceError::InvariantViolation);
    }
    for item in &snapshot.items {
        if item.item_id == 0 || item.item_id >= i64::MAX as u64 || !item_ids.insert(item.item_id) {
            return Err(WorkspaceError::InvariantViolation);
        }
        match &item.payload {
            ItemPayload::Application(component) => {
                validate_component(component)?;
                if !components.insert(component.clone()) {
                    return Err(WorkspaceError::InvariantViolation);
                }
            }
            ItemPayload::Folder if !folder_ids.contains(&item.item_id) => {
                return Err(WorkspaceError::InvariantViolation);
            }
            ItemPayload::Folder => {}
            ItemPayload::Shortcut(shortcut) => {
                validate_shortcut(shortcut)?;
                if !shortcuts.insert(shortcut.clone()) {
                    return Err(WorkspaceError::InvariantViolation);
                }
            }
            ItemPayload::Widget(provider) => {
                validate_widget_provider(provider)?;
                if matches!(item.container, ContainerRef::Hotseat) {
                    return Err(WorkspaceError::InvariantViolation);
                }
            }
        }
        validate_destination(snapshot, &item.container, item.cell)?;
    }
    if snapshot.folders.iter().any(|folder| {
        !snapshot
            .items
            .iter()
            .any(|item| item.item_id == folder.folder_id && item.payload == ItemPayload::Folder)
    }) {
        return Err(WorkspaceError::InvariantViolation);
    }
    for folder in &snapshot.folders {
        validate_title(&folder.title)?;
        if folder.folder_id == 0 || folder.folder_id >= i64::MAX as u64 || folder.members.len() < 2
        {
            return Err(WorkspaceError::InvariantViolation);
        }
        let profile = payload_profile(&folder.members[0].payload)?;
        for (rank, member) in folder.members.iter().enumerate() {
            match &member.payload {
                ItemPayload::Application(component) => {
                    validate_component(component)?;
                    if !components.insert(component.clone()) {
                        return Err(WorkspaceError::InvariantViolation);
                    }
                }
                ItemPayload::Shortcut(shortcut) => {
                    validate_shortcut(shortcut)?;
                    if !shortcuts.insert(shortcut.clone()) {
                        return Err(WorkspaceError::InvariantViolation);
                    }
                }
                ItemPayload::Folder | ItemPayload::Widget(_) => {
                    return Err(WorkspaceError::InvariantViolation);
                }
            }
            if member.rank != rank as u32
                || payload_profile(&member.payload)? != profile
                || member.item_id == 0
                || member.item_id >= i64::MAX as u64
                || !item_ids.insert(member.item_id)
            {
                return Err(WorkspaceError::InvariantViolation);
            }
        }
    }
    for (index, left) in snapshot.items.iter().enumerate() {
        if snapshot.items[index + 1..]
            .iter()
            .any(|right| left.container == right.container && overlaps(left.cell, right.cell))
        {
            return Err(WorkspaceError::InvariantViolation);
        }
    }
    Ok(())
}

fn validate_grid(grid: GridSpec) -> Result<(), WorkspaceError> {
    if grid.cols <= 0
        || grid.rows <= 0
        || grid.hotseat_cols <= 0
        || grid.cols > 64
        || grid.rows > 64
        || grid.hotseat_cols > 64
    {
        return Err(WorkspaceError::InvalidGrid);
    }
    Ok(())
}

fn normalize_pages(pages: &mut [WorkspacePage]) -> Result<(), WorkspaceError> {
    if pages.is_empty() {
        return Err(WorkspaceError::MissingPage);
    }
    pages.sort_by_key(|page| (page.rank, page.page_id));
    for (rank, page) in pages.iter_mut().enumerate() {
        page.rank = rank as i32;
    }
    Ok(())
}

fn normalize_members(members: &mut [FolderMember]) {
    for (rank, member) in members.iter_mut().enumerate() {
        member.rank = rank as u32;
    }
}

fn normalize_folders(folders: &mut [Folder]) {
    folders.sort_by_key(|folder| folder.folder_id);
    for folder in folders {
        normalize_members(&mut folder.members);
    }
}

fn sort_snapshot(snapshot: &mut WorkspaceSnapshot) {
    let ranks: HashMap<_, _> = snapshot
        .pages
        .iter()
        .map(|page| (page.page_id, page.rank))
        .collect();
    snapshot.items.sort_by_key(|item| {
        let (kind, rank) = match item.container {
            ContainerRef::Hotseat => (0, 0),
            ContainerRef::Workspace { page_id } => (1, ranks[&page_id]),
        };
        (kind, rank, item.cell.cell_y, item.cell.cell_x, item.item_id)
    });
}

pub(crate) fn prepare_snapshot(snapshot: &mut WorkspaceSnapshot) -> Result<(), WorkspaceError> {
    normalize_pages(&mut snapshot.pages)?;
    trim_empty_trailing_pages(snapshot);
    normalize_pages(&mut snapshot.pages)?;
    normalize_folders(&mut snapshot.folders);
    sort_snapshot(snapshot);
    validate_snapshot(snapshot)
}

fn changed_items(before: &WorkspaceSnapshot, after: &WorkspaceSnapshot) -> Vec<u64> {
    let old_items: HashMap<_, _> = before
        .items
        .iter()
        .map(|item| (item.item_id, item))
        .collect();
    let new_items: HashMap<_, _> = after
        .items
        .iter()
        .map(|item| (item.item_id, item))
        .collect();
    let old_folders: HashMap<_, _> = before
        .folders
        .iter()
        .map(|folder| (folder.folder_id, folder))
        .collect();
    let new_folders: HashMap<_, _> = after
        .folders
        .iter()
        .map(|folder| (folder.folder_id, folder))
        .collect();
    let old_members: HashMap<_, _> = before
        .folders
        .iter()
        .flat_map(|folder| folder.members.iter())
        .map(|member| (member.item_id, member))
        .collect();
    let new_members: HashMap<_, _> = after
        .folders
        .iter()
        .flat_map(|folder| folder.members.iter())
        .map(|member| (member.item_id, member))
        .collect();
    let mut ids: Vec<_> = old_items
        .keys()
        .chain(new_items.keys())
        .filter(|id| old_items.get(id) != new_items.get(id))
        .chain(
            old_folders
                .keys()
                .chain(new_folders.keys())
                .filter(|id| old_folders.get(id) != new_folders.get(id)),
        )
        .chain(
            old_members
                .keys()
                .chain(new_members.keys())
                .filter(|id| old_members.get(id) != new_members.get(id)),
        )
        .copied()
        .collect::<HashSet<_>>()
        .into_iter()
        .collect();
    ids.sort_unstable();
    ids
}

impl From<WorkspaceTransition> for WorkspaceSnapshot {
    fn from(value: WorkspaceTransition) -> Self {
        Self {
            generation: value.generation,
            grid: value.grid,
            pages: value.pages,
            items: value.items,
            folders: value.folders,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn component(name: &str) -> ComponentId {
        ComponentId {
            package: name.into(),
            class: "Main".into(),
            profile_id: 0,
        }
    }

    fn shortcut(name: &str) -> ShortcutId {
        ShortcutId {
            package: "com.example".into(),
            shortcut_id: name.into(),
            profile_id: 0,
        }
    }

    fn snapshot() -> WorkspaceSnapshot {
        WorkspaceSnapshot {
            generation: 4,
            grid: GridSpec {
                cols: 2,
                rows: 2,
                hotseat_cols: 2,
            },
            pages: vec![WorkspacePage {
                page_id: 10,
                rank: 0,
            }],
            items: Vec::new(),
            folders: Vec::new(),
        }
    }

    fn place(id: u64, x: i32, y: i32) -> WorkspaceCommand {
        WorkspaceCommand::PlaceFromAllApps {
            expected_generation: 4,
            item_id: id,
            component: component(&format!("p{id}")),
            page_id: 10,
            cell: CellRect::single(x, y),
        }
    }

    #[test]
    fn every_command_and_dynamic_page_lifecycle() {
        let mut state = apply_workspace_command(snapshot(), place(1, 0, 0)).unwrap();
        assert_eq!(state.generation, 5);
        state = apply_workspace_command(
            state.into(),
            WorkspaceCommand::AddPage {
                expected_generation: 5,
                page_id: 20,
            },
        )
        .unwrap();
        assert_eq!(state.created_page_ids, [20]);
        state = apply_workspace_command(
            state.into(),
            WorkspaceCommand::Move {
                expected_generation: 6,
                item_id: 1,
                container: ContainerRef::Workspace { page_id: 20 },
                cell: CellRect::single(0, 0),
            },
        )
        .unwrap();
        assert_eq!(state.removed_page_ids, Vec::<u64>::new());
        state = apply_workspace_command(
            state.into(),
            WorkspaceCommand::Dock {
                expected_generation: 7,
                item_id: 1,
                rank: 0,
            },
        )
        .unwrap();
        assert_eq!(state.pages.len(), 1);
        assert_eq!(state.removed_page_ids, [20]);
        state = apply_workspace_command(
            state.into(),
            WorkspaceCommand::Undock {
                expected_generation: 8,
                item_id: 1,
                page_id: 10,
                cell: CellRect::single(1, 1),
            },
        )
        .unwrap();
        assert!(matches!(
            state.items[0].container,
            ContainerRef::Workspace { page_id: 10 }
        ));
        state = apply_workspace_command(
            state.into(),
            WorkspaceCommand::DropMissing {
                expected_generation: 9,
                live: vec![],
                authoritative_profile_ids: vec![0],
            },
        )
        .unwrap();
        assert!(state.items.is_empty());
    }

    #[test]
    fn stale_and_failed_commands_do_not_mutate_input() {
        let source = snapshot();
        assert_eq!(
            apply_workspace_command(
                source.clone(),
                WorkspaceCommand::Cancelled {
                    expected_generation: 3
                }
            ),
            Err(WorkspaceError::StaleGeneration)
        );
        let occupied = apply_workspace_command(source, place(1, 0, 0)).unwrap();
        let before = WorkspaceSnapshot::from(occupied.clone());
        let error = apply_workspace_command(
            before.clone(),
            WorkspaceCommand::PlaceFromAllApps {
                expected_generation: 5,
                item_id: 2,
                component: component("other"),
                page_id: 10,
                cell: CellRect::single(0, 0),
            },
        );
        assert_eq!(error, Err(WorkspaceError::Occupied));
        assert_eq!(before.items.len(), 1);
    }

    #[test]
    fn no_op_preserves_generation_and_state() {
        let placed = apply_workspace_command(snapshot(), place(1, 0, 0)).unwrap();
        let before = WorkspaceSnapshot::from(placed);
        let transition = apply_workspace_command(
            before.clone(),
            WorkspaceCommand::Move {
                expected_generation: before.generation,
                item_id: 1,
                container: ContainerRef::Workspace { page_id: 10 },
                cell: CellRect::single(0, 0),
            },
        )
        .unwrap();
        assert_eq!(transition.generation, before.generation);
        assert_eq!(WorkspaceSnapshot::from(transition), before);
    }

    #[test]
    fn drop_missing_trims_every_empty_trailing_page() {
        let mut source = snapshot();
        source.pages.extend([
            WorkspacePage {
                page_id: 20,
                rank: 1,
            },
            WorkspacePage {
                page_id: 30,
                rank: 2,
            },
        ]);
        let transition = apply_workspace_command(
            source,
            WorkspaceCommand::DropMissing {
                expected_generation: 4,
                live: vec![],
                authoritative_profile_ids: vec![0],
            },
        )
        .unwrap();
        assert_eq!(transition.removed_page_ids, [20, 30]);
        assert_eq!(
            transition.pages,
            [WorkspacePage {
                page_id: 10,
                rank: 0
            }]
        );
        assert_eq!(transition.generation, 5);
    }

    #[test]
    fn reorder_uses_nearest_then_row_column_tie_break() {
        let first = apply_workspace_command(snapshot(), place(1, 0, 0)).unwrap();
        let second = apply_workspace_command(
            first.into(),
            WorkspaceCommand::PlaceFromAllApps {
                expected_generation: 5,
                item_id: 2,
                component: component("second"),
                page_id: 10,
                cell: CellRect::single(1, 0),
            },
        )
        .unwrap();
        let moved = apply_workspace_command(
            second.into(),
            WorkspaceCommand::Reorder {
                expected_generation: 6,
                item_id: 1,
                container: ContainerRef::Workspace { page_id: 10 },
                cell: CellRect::single(1, 0),
            },
        )
        .unwrap();
        assert_eq!(
            moved
                .items
                .iter()
                .find(|item| item.item_id == 1)
                .unwrap()
                .cell,
            CellRect::single(1, 0)
        );
        assert_eq!(
            moved
                .items
                .iter()
                .find(|item| item.item_id == 2)
                .unwrap()
                .cell,
            CellRect::single(0, 0)
        );
    }

    #[test]
    fn invalid_snapshots_and_grids_are_rejected() {
        let mut invalid = snapshot();
        invalid.pages.clear();
        assert_eq!(
            apply_workspace_command(
                invalid,
                WorkspaceCommand::Cancelled {
                    expected_generation: 4
                }
            ),
            Err(WorkspaceError::MissingPage)
        );
        let source = snapshot();
        assert_eq!(
            apply_workspace_command(
                source,
                WorkspaceCommand::SetGrid {
                    expected_generation: 4,
                    grid: GridSpec {
                        cols: 0,
                        rows: 1,
                        hotseat_cols: 1
                    },
                    next_page_id: 20,
                    widget_minimum_spans: vec![],
                }
            ),
            Err(WorkspaceError::InvalidGrid)
        );
    }

    #[test]
    fn grid_migration_preserves_ids_and_reflows_hotseat_overflow_to_new_pages() {
        let mut source = snapshot();
        source.items = vec![
            WorkspaceItem {
                item_id: 1,
                payload: ItemPayload::Application(component("one")),
                container: ContainerRef::Workspace { page_id: 10 },
                cell: CellRect::single(0, 0),
            },
            WorkspaceItem {
                item_id: 2,
                payload: ItemPayload::Application(component("two")),
                container: ContainerRef::Workspace { page_id: 10 },
                cell: CellRect::single(1, 0),
            },
            WorkspaceItem {
                item_id: 3,
                payload: ItemPayload::Application(component("three")),
                container: ContainerRef::Hotseat,
                cell: CellRect::single(0, 0),
            },
            WorkspaceItem {
                item_id: 4,
                payload: ItemPayload::Application(component("four")),
                container: ContainerRef::Hotseat,
                cell: CellRect::single(1, 0),
            },
        ];
        let command = WorkspaceCommand::SetGrid {
            expected_generation: 4,
            grid: GridSpec {
                cols: 1,
                rows: 1,
                hotseat_cols: 1,
            },
            next_page_id: 20,
            widget_minimum_spans: vec![],
        };
        let left = apply_workspace_command(source.clone(), command.clone()).unwrap();
        let right = apply_workspace_command(source, command).unwrap();

        assert_eq!(left, right);
        assert_eq!(left.generation, 5);
        assert_eq!(left.created_page_ids, [20, 21]);
        assert_eq!(
            left.items
                .iter()
                .map(|item| item.item_id)
                .collect::<HashSet<_>>(),
            HashSet::from([1, 2, 3, 4])
        );
        assert!(
            left.items
                .iter()
                .any(|item| item.item_id == 3 && item.container == ContainerRef::Hotseat)
        );
        assert!(
            left.items.iter().any(|item| item.item_id == 4
                && item.container == ContainerRef::Workspace { page_id: 20 })
        );
    }

    #[test]
    fn grid_migration_reports_impossible_widget_minimum_without_mutation() {
        let mut source = snapshot();
        source.items.push(WorkspaceItem {
            item_id: 1,
            payload: ItemPayload::Widget(WidgetProviderId {
                package: "widgets".into(),
                class: "Clock".into(),
                profile_id: 0,
            }),
            container: ContainerRef::Workspace { page_id: 10 },
            cell: CellRect {
                cell_x: 0,
                cell_y: 0,
                span_x: 2,
                span_y: 2,
            },
        });
        let before = source.clone();
        assert_eq!(
            apply_workspace_command(
                source,
                WorkspaceCommand::SetGrid {
                    expected_generation: 4,
                    grid: GridSpec {
                        cols: 1,
                        rows: 2,
                        hotseat_cols: 1
                    },
                    next_page_id: 20,
                    widget_minimum_spans: vec![WidgetMinimumSpan {
                        item_id: 1,
                        span_x: 2,
                        span_y: 1
                    }],
                },
            ),
            Err(WorkspaceError::WidgetTooLarge),
        );
        assert_eq!(before.grid.cols, 2);
        assert_eq!(before.items[0].cell.span_x, 2);
    }

    #[test]
    fn grid_expansion_and_repeat_preserve_positions_and_generation() {
        let mut source = snapshot();
        source.items.push(WorkspaceItem {
            item_id: 1,
            payload: ItemPayload::Widget(WidgetProviderId {
                package: "widgets".into(),
                class: "Clock".into(),
                profile_id: 0,
            }),
            container: ContainerRef::Workspace { page_id: 10 },
            cell: CellRect {
                cell_x: 0,
                cell_y: 0,
                span_x: 2,
                span_y: 2,
            },
        });
        let expanded = apply_workspace_command(
            source,
            WorkspaceCommand::SetGrid {
                expected_generation: 4,
                grid: GridSpec {
                    cols: 3,
                    rows: 3,
                    hotseat_cols: 3,
                },
                next_page_id: 20,
                widget_minimum_spans: vec![WidgetMinimumSpan {
                    item_id: 1,
                    span_x: 1,
                    span_y: 1,
                }],
            },
        )
        .unwrap();
        assert_eq!(
            expanded.items[0].cell,
            CellRect {
                cell_x: 0,
                cell_y: 0,
                span_x: 2,
                span_y: 2
            }
        );

        let repeated = apply_workspace_command(
            expanded.clone().into(),
            WorkspaceCommand::SetGrid {
                expected_generation: 5,
                grid: expanded.grid,
                next_page_id: 20,
                widget_minimum_spans: vec![WidgetMinimumSpan {
                    item_id: 1,
                    span_x: 1,
                    span_y: 1,
                }],
            },
        )
        .unwrap();
        assert_eq!(repeated.generation, 5);
        assert_eq!(
            WorkspaceSnapshot::from(repeated),
            WorkspaceSnapshot::from(expanded.clone())
        );
        assert_eq!(
            apply_workspace_command(
                expanded.into(),
                WorkspaceCommand::SetGrid {
                    expected_generation: 4,
                    grid: GridSpec {
                        cols: 2,
                        rows: 2,
                        hotseat_cols: 2
                    },
                    next_page_id: 20,
                    widget_minimum_spans: vec![],
                },
            ),
            Err(WorkspaceError::StaleGeneration),
        );
    }

    #[test]
    fn property_command_sequences_preserve_invariants_and_determinism() {
        for seed in 1..64_u64 {
            let mut state = snapshot();
            for step in 0..32_u64 {
                let id = seed * 100 + step + 1;
                let command = if step % 3 == 0 {
                    WorkspaceCommand::PlaceShortcut {
                        expected_generation: state.generation,
                        item_id: id,
                        shortcut: ShortcutId {
                            package: format!("p{seed}"),
                            shortcut_id: format!("s{step}"),
                            profile_id: 0,
                        },
                        container: ContainerRef::Workspace { page_id: 10 },
                        cell: CellRect::single((step % 2) as i32, ((step / 2) % 2) as i32),
                    }
                } else {
                    WorkspaceCommand::PlaceFromAllApps {
                        expected_generation: state.generation,
                        item_id: id,
                        component: component(&format!("{seed}.{step}")),
                        page_id: 10,
                        cell: CellRect::single((step % 2) as i32, ((step / 2) % 2) as i32),
                    }
                };
                let left = apply_workspace_command(state.clone(), command.clone());
                let right = apply_workspace_command(state.clone(), command);
                assert_eq!(left, right);
                if let Ok(transition) = left {
                    state = transition.into();
                    validate_snapshot(&state).unwrap();
                }
            }
        }
    }

    #[test]
    fn folder_lifecycle_is_atomic_and_preserves_item_identity() {
        let mut state = snapshot();
        state.generation = 0;
        state.grid = GridSpec {
            cols: 4,
            rows: 5,
            hotseat_cols: 4,
        };
        for (item_id, name, profile_id, x) in [
            (1, "source", 0, 0),
            (2, "destination", 0, 1),
            (3, "other-profile", 1, 2),
            (4, "placed", 0, 3),
        ] {
            state = apply_workspace_command(
                state,
                WorkspaceCommand::PlaceFromAllApps {
                    expected_generation: item_id - 1,
                    item_id,
                    component: ComponentId {
                        package: "com.example".into(),
                        class: name.into(),
                        profile_id,
                    },
                    page_id: 10,
                    cell: CellRect::single(x, 0),
                },
            )
            .unwrap()
            .into();
        }

        state = apply_workspace_command(
            state,
            WorkspaceCommand::CreateFolder {
                expected_generation: 4,
                folder_id: 10,
                first_item_id: 1,
                second_item_id: 2,
            },
        )
        .unwrap()
        .into();
        assert_eq!(
            state.folders[0]
                .members
                .iter()
                .map(|member| member.item_id)
                .collect::<Vec<_>>(),
            vec![2, 1]
        );
        assert!(state.items.iter().any(|item| {
            item.item_id == 10
                && item.payload == ItemPayload::Folder
                && item.cell == CellRect::single(1, 0)
        }));

        let unchanged = state.clone();
        assert_eq!(
            apply_workspace_command(
                state.clone(),
                WorkspaceCommand::AddItemToFolder {
                    expected_generation: 5,
                    item_id: 3,
                    folder_id: 10,
                    rank: 0,
                }
            ),
            Err(WorkspaceError::CrossProfile)
        );
        assert_eq!(state, unchanged);

        for command in [
            WorkspaceCommand::AddItemToFolder {
                expected_generation: 5,
                item_id: 4,
                folder_id: 10,
                rank: 1,
            },
            WorkspaceCommand::AddFromAllAppsToFolder {
                expected_generation: 6,
                item_id: 5,
                component: component("from-drawer"),
                folder_id: 10,
                rank: 2,
            },
            WorkspaceCommand::MoveFolderMember {
                expected_generation: 7,
                folder_id: 10,
                item_id: 1,
                rank: 0,
            },
            WorkspaceCommand::RenameFolder {
                expected_generation: 8,
                folder_id: 10,
                title: "Tools".into(),
            },
        ] {
            state = apply_workspace_command(state, command).unwrap().into();
        }
        assert_eq!(state.folders[0].title, "Tools");
        assert_eq!(
            state.folders[0]
                .members
                .iter()
                .map(|member| (member.item_id, member.rank))
                .collect::<Vec<_>>(),
            vec![(1, 0), (2, 1), (4, 2), (5, 3)]
        );
        assert_eq!(
            apply_workspace_command(
                state.clone(),
                WorkspaceCommand::RenameFolder {
                    expected_generation: 9,
                    folder_id: 10,
                    title: "bad\nname".into(),
                }
            ),
            Err(WorkspaceError::InvalidTitle)
        );

        for (generation, item_id, x) in [(9, 1, 0), (10, 2, 2), (11, 5, 3)] {
            state = apply_workspace_command(
                state,
                WorkspaceCommand::RemoveItemFromFolder {
                    expected_generation: generation,
                    folder_id: 10,
                    item_id,
                    container: ContainerRef::Workspace { page_id: 10 },
                    cell: CellRect::single(x, 1),
                },
            )
            .unwrap()
            .into();
        }
        assert!(state.folders.is_empty());
        assert!(state.items.iter().any(|item| {
            item.item_id == 4
                && matches!(item.payload, ItemPayload::Application(_))
                && item.cell == CellRect::single(1, 0)
        }));
        validate_snapshot(&state).unwrap();
    }

    #[test]
    fn package_reconciliation_dissolves_single_member_folder() {
        let mut state = snapshot();
        state.generation = 0;
        for (item_id, name, x) in [(1, "keep", 0), (2, "remove", 1)] {
            state = apply_workspace_command(
                state,
                WorkspaceCommand::PlaceFromAllApps {
                    expected_generation: item_id - 1,
                    item_id,
                    component: component(name),
                    page_id: 10,
                    cell: CellRect::single(x, 0),
                },
            )
            .unwrap()
            .into();
        }
        state = apply_workspace_command(
            state,
            WorkspaceCommand::CreateFolder {
                expected_generation: 2,
                folder_id: 3,
                first_item_id: 1,
                second_item_id: 2,
            },
        )
        .unwrap()
        .into();
        state = apply_workspace_command(
            state,
            WorkspaceCommand::DropMissing {
                expected_generation: 3,
                live: vec![component("keep")],
                authoritative_profile_ids: vec![0],
            },
        )
        .unwrap()
        .into();

        assert!(state.folders.is_empty());
        assert_eq!(state.items[0].item_id, 1);
        assert_eq!(state.items[0].cell, CellRect::single(1, 0));
    }

    #[test]
    fn inaccessible_profile_is_not_package_removal() {
        let mut state = snapshot();
        state.generation = 0;
        for (item_id, profile_id, x) in [(1, 0, 0), (2, 1, 1)] {
            state = apply_workspace_command(
                state,
                WorkspaceCommand::PlaceFromAllApps {
                    expected_generation: item_id - 1,
                    item_id,
                    component: ComponentId {
                        package: "com.example.same".into(),
                        class: "Main".into(),
                        profile_id,
                    },
                    page_id: 10,
                    cell: CellRect::single(x, 0),
                },
            )
            .unwrap()
            .into();
        }
        let unchanged = apply_workspace_command(
            state.clone(),
            WorkspaceCommand::DropMissing {
                expected_generation: 2,
                live: vec![ComponentId {
                    package: "com.example.same".into(),
                    class: "Main".into(),
                    profile_id: 0,
                }],
                authoritative_profile_ids: vec![0],
            },
        )
        .unwrap();
        assert_eq!(unchanged.generation, 2);
        assert_eq!(unchanged.items.len(), 2);

        let removed = apply_workspace_command(
            unchanged.into(),
            WorkspaceCommand::RemoveProfiles {
                expected_generation: 2,
                profile_ids: vec![1],
            },
        )
        .unwrap();
        assert_eq!(removed.generation, 3);
        assert_eq!(removed.items.len(), 1);
        assert!(matches!(
            &removed.items[0].payload,
            ItemPayload::Application(component) if component.profile_id == 0
        ));
    }

    #[test]
    fn shortcut_identity_placement_folder_and_reconciliation() {
        let mut state = snapshot();
        state.generation = 0;
        state = apply_workspace_command(
            state,
            WorkspaceCommand::PlaceFromAllApps {
                expected_generation: 0,
                item_id: 1,
                component: ComponentId {
                    package: "com.example".into(),
                    class: "Main".into(),
                    profile_id: 0,
                },
                page_id: 10,
                cell: CellRect::single(0, 0),
            },
        )
        .unwrap()
        .into();
        state = apply_workspace_command(
            state,
            WorkspaceCommand::PlaceShortcut {
                expected_generation: 1,
                item_id: 2,
                shortcut: shortcut("manifest"),
                container: ContainerRef::Workspace { page_id: 10 },
                cell: CellRect::single(1, 0),
            },
        )
        .unwrap()
        .into();
        assert_eq!(state.items.len(), 2, "parent app and shortcut coexist");

        let duplicate = apply_workspace_command(
            state.clone(),
            WorkspaceCommand::PlaceShortcut {
                expected_generation: 2,
                item_id: 3,
                shortcut: shortcut("manifest"),
                container: ContainerRef::Workspace { page_id: 10 },
                cell: CellRect::single(0, 1),
            },
        );
        assert_eq!(duplicate, Err(WorkspaceError::InvariantViolation));

        state = apply_workspace_command(
            state,
            WorkspaceCommand::CreateFolder {
                expected_generation: 2,
                folder_id: 3,
                first_item_id: 2,
                second_item_id: 1,
            },
        )
        .unwrap()
        .into();
        state = apply_workspace_command(
            state,
            WorkspaceCommand::AddShortcutToFolder {
                expected_generation: 3,
                item_id: 4,
                shortcut: shortcut("dynamic"),
                folder_id: 3,
                rank: 0,
            },
        )
        .unwrap()
        .into();
        assert_eq!(
            state.folders[0]
                .members
                .iter()
                .map(|m| m.rank)
                .collect::<Vec<_>>(),
            [0, 1, 2]
        );

        let cross_profile = apply_workspace_command(
            state.clone(),
            WorkspaceCommand::AddShortcutToFolder {
                expected_generation: 4,
                item_id: 5,
                shortcut: ShortcutId {
                    package: "com.example".into(),
                    shortcut_id: "work".into(),
                    profile_id: 1,
                },
                folder_id: 3,
                rank: 0,
            },
        );
        assert_eq!(cross_profile, Err(WorkspaceError::CrossProfile));

        state = apply_workspace_command(
            state,
            WorkspaceCommand::ReconcileShortcuts {
                expected_generation: 4,
                live: vec![shortcut("manifest")],
                authoritative_profile_ids: vec![0],
            },
        )
        .unwrap()
        .into();
        assert_eq!(state.folders[0].members.len(), 2);
        assert!(state.folders[0].members.iter().any(|member| {
            matches!(&member.payload, ItemPayload::Shortcut(id) if id.shortcut_id == "manifest")
        }));

        let invalid = apply_workspace_command(
            state,
            WorkspaceCommand::PlaceShortcut {
                expected_generation: 5,
                item_id: 6,
                shortcut: shortcut(""),
                container: ContainerRef::Workspace { page_id: 10 },
                cell: CellRect::single(0, 1),
            },
        );
        assert_eq!(invalid, Err(WorkspaceError::InvariantViolation));
    }

    #[test]
    fn widgets_allow_instances_and_reject_invalid_containers_and_resize() {
        let provider = WidgetProviderId {
            package: "com.example.widgets".into(),
            class: "ClockProvider".into(),
            profile_id: 0,
        };
        let mut state = apply_workspace_command(
            snapshot(),
            WorkspaceCommand::PlaceWidget {
                expected_generation: 4,
                item_id: 1,
                provider: provider.clone(),
                page_id: 10,
                cell: CellRect {
                    cell_x: 0,
                    cell_y: 0,
                    span_x: 2,
                    span_y: 1,
                },
            },
        )
        .unwrap()
        .into();
        state = apply_workspace_command(
            state,
            WorkspaceCommand::PlaceWidget {
                expected_generation: 5,
                item_id: 2,
                provider,
                page_id: 10,
                cell: CellRect::single(0, 1),
            },
        )
        .unwrap()
        .into();

        assert_eq!(
            apply_workspace_command(
                state.clone(),
                WorkspaceCommand::ResizeWidget {
                    expected_generation: 6,
                    item_id: 1,
                    cell: CellRect {
                        cell_x: 0,
                        cell_y: 0,
                        span_x: 1,
                        span_y: 2,
                    },
                },
            ),
            Err(WorkspaceError::Occupied)
        );
        state = apply_workspace_command(
            state,
            WorkspaceCommand::ResizeWidget {
                expected_generation: 6,
                item_id: 1,
                cell: CellRect {
                    cell_x: 1,
                    cell_y: 0,
                    span_x: 1,
                    span_y: 2,
                },
            },
        )
        .unwrap()
        .into();
        assert_eq!(
            state
                .items
                .iter()
                .find(|item| item.item_id == 1)
                .unwrap()
                .cell,
            CellRect {
                cell_x: 1,
                cell_y: 0,
                span_x: 1,
                span_y: 2,
            }
        );
        assert_eq!(
            apply_workspace_command(
                state,
                WorkspaceCommand::Dock {
                    expected_generation: 7,
                    item_id: 1,
                    rank: 0,
                },
            ),
            Err(WorkspaceError::InvariantViolation)
        );
    }
}
