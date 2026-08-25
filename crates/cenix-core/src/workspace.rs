use std::collections::{HashMap, HashSet};

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub struct ComponentId {
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

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub enum ContainerRef {
    Workspace { page_id: u64 },
    Hotseat,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ItemKind {
    Application,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorkspacePage {
    pub page_id: u64,
    pub rank: i32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorkspaceItem {
    pub item_id: u64,
    pub component: ComponentId,
    pub container: ContainerRef,
    pub cell: CellRect,
    pub kind: ItemKind,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorkspaceSnapshot {
    pub generation: u64,
    pub grid: GridSpec,
    pub pages: Vec<WorkspacePage>,
    pub items: Vec<WorkspaceItem>,
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

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorkspaceTransition {
    pub generation: u64,
    pub grid: GridSpec,
    pub pages: Vec<WorkspacePage>,
    pub items: Vec<WorkspaceItem>,
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
    InvalidProfile,
    Full,
    StaleGeneration,
    InvalidGrid,
    InvariantViolation,
}

impl WorkspaceCommand {
    fn expected_generation(&self) -> u64 {
        match self {
            Self::PlaceFromAllApps {
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
        WorkspaceCommand::SetGrid { grid, .. } => {
            snapshot.grid = grid;
            validate_snapshot(&snapshot)?;
        }
        WorkspaceCommand::DropMissing { live, .. } => {
            for component in &live {
                validate_component(component)?;
            }
            let live: HashSet<_> = live.into_iter().collect();
            snapshot.items.retain(|item| live.contains(&item.component));
        }
        WorkspaceCommand::Cancelled { .. } => {}
    }

    if trim {
        removed_page_ids.extend(trim_empty_trailing_pages(&mut snapshot));
    }
    normalize_pages(&mut snapshot.pages)?;
    validate_snapshot(&snapshot)?;
    sort_snapshot(&mut snapshot);

    let changed_item_ids = changed_items(&before.items, &snapshot.items);
    let changed = before.grid != snapshot.grid
        || before.pages != snapshot.pages
        || before.items != snapshot.items;
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
    if item_id == 0
        || item_id >= i64::MAX as u64
        || snapshot.items.iter().any(|item| item.item_id == item_id)
        || snapshot
            .items
            .iter()
            .any(|item| item.component == component)
    {
        return Err(WorkspaceError::InvariantViolation);
    }
    let container = ContainerRef::Workspace { page_id };
    validate_destination(snapshot, &container, cell)?;
    if occupied(snapshot, &container, cell, None) {
        return Err(WorkspaceError::Occupied);
    }
    snapshot.items.push(WorkspaceItem {
        item_id,
        component,
        container,
        cell,
        kind: ItemKind::Application,
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
    validate_destination(snapshot, &container, cell)?;
    let index = snapshot
        .items
        .iter()
        .position(|item| item.item_id == item_id)
        .ok_or(WorkspaceError::MissingItem)?;
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
    let len = snapshot.items.len();
    snapshot.items.retain(|item| item.item_id != item_id);
    if snapshot.items.len() == len {
        return Err(WorkspaceError::MissingItem);
    }
    Ok(())
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

fn validate_snapshot(snapshot: &WorkspaceSnapshot) -> Result<(), WorkspaceError> {
    if snapshot.grid.cols <= 0
        || snapshot.grid.rows <= 0
        || snapshot.grid.hotseat_cols <= 0
        || snapshot.grid.cols > 64
        || snapshot.grid.rows > 64
        || snapshot.grid.hotseat_cols > 64
    {
        return Err(WorkspaceError::InvalidGrid);
    }
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
    for item in &snapshot.items {
        validate_component(&item.component)?;
        if item.item_id == 0
            || item.item_id >= i64::MAX as u64
            || !item_ids.insert(item.item_id)
            || !components.insert(item.component.clone())
        {
            return Err(WorkspaceError::InvariantViolation);
        }
        validate_destination(snapshot, &item.container, item.cell)?;
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

fn changed_items(before: &[WorkspaceItem], after: &[WorkspaceItem]) -> Vec<u64> {
    let old: HashMap<_, _> = before.iter().map(|item| (item.item_id, item)).collect();
    let new: HashMap<_, _> = after.iter().map(|item| (item.item_id, item)).collect();
    let mut ids: Vec<_> = old
        .keys()
        .chain(new.keys())
        .copied()
        .collect::<HashSet<_>>()
        .into_iter()
        .filter(|id| old.get(id) != new.get(id))
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
                    }
                }
            ),
            Err(WorkspaceError::InvalidGrid)
        );
    }

    #[test]
    fn property_command_sequences_preserve_invariants_and_determinism() {
        for seed in 1..64_u64 {
            let mut state = snapshot();
            for step in 0..32_u64 {
                let id = seed * 100 + step + 1;
                let command = WorkspaceCommand::PlaceFromAllApps {
                    expected_generation: state.generation,
                    item_id: id,
                    component: component(&format!("{seed}.{step}")),
                    page_id: 10,
                    cell: CellRect::single((step % 2) as i32, ((step / 2) % 2) as i32),
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
}
