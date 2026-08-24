pub const SCREENS: i32 = 2;
pub const HOTSEAT: i32 = -1;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorkspaceItem {
    pub package: String,
    pub class: String,
    pub profile_id: u64,
    pub screen: i32,
    pub cell_x: i32,
    pub cell_y: i32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorkspaceSnapshot {
    pub items: Vec<WorkspaceItem>,
    pub cols: i32,
    pub rows: i32,
    pub screens: i32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
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
        live: Vec<(String, String, u64)>,
    },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorkspaceTransition {
    pub items: Vec<WorkspaceItem>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WorkspaceError {
    Occupied,
    OutOfBounds,
    MissingItem,
    InvalidProfile,
    Full,
}

impl WorkspaceItem {
    fn same_app(&self, package: &str, class: &str, profile_id: u64) -> bool {
        self.package == package && self.class == class && self.profile_id == profile_id
    }
}

pub fn apply_workspace_command(
    snapshot: WorkspaceSnapshot,
    command: WorkspaceCommand,
) -> Result<WorkspaceTransition, WorkspaceError> {
    if snapshot.cols <= 0 || snapshot.rows <= 0 || snapshot.screens <= 0 {
        return Err(WorkspaceError::OutOfBounds);
    }
    let dims = WorkspaceSnapshot {
        items: Vec::new(),
        cols: snapshot.cols,
        rows: snapshot.rows,
        screens: snapshot.screens,
    };
    let mut items = snapshot.items;
    match command {
        WorkspaceCommand::Place {
            package,
            class,
            profile_id,
            screen,
            cell_x,
            cell_y,
        } => place(
            &mut items,
            &dims,
            &package,
            &class,
            profile_id,
            (screen, cell_x, cell_y),
        )?,
        WorkspaceCommand::Remove {
            package,
            class,
            profile_id,
        } => {
            require_id(&package, &class)?;
            items.retain(|item| !item.same_app(&package, &class, profile_id));
        }
        WorkspaceCommand::Dock {
            package,
            class,
            profile_id,
        } => dock(&mut items, &dims, &package, &class, profile_id)?,
        WorkspaceCommand::Pin {
            package,
            class,
            profile_id,
            preferred_screen,
        } => pin(
            &mut items,
            &dims,
            &package,
            &class,
            profile_id,
            preferred_screen,
        )?,
        WorkspaceCommand::DropMissing { live } => {
            items.retain(|item| {
                live.iter()
                    .any(|(package, class, profile)| item.same_app(package, class, *profile))
            });
        }
    }
    items.sort_by(|a, b| {
        a.screen
            .cmp(&b.screen)
            .then(a.cell_y.cmp(&b.cell_y))
            .then(a.cell_x.cmp(&b.cell_x))
            .then(a.package.cmp(&b.package))
    });
    Ok(WorkspaceTransition { items })
}

fn require_id(package: &str, class: &str) -> Result<(), WorkspaceError> {
    if package.is_empty() || class.is_empty() {
        return Err(WorkspaceError::InvalidProfile);
    }
    Ok(())
}

fn in_bounds(snapshot: &WorkspaceSnapshot, screen: i32, cell_x: i32, cell_y: i32) -> bool {
    if screen == HOTSEAT {
        return cell_x >= 0 && cell_x < snapshot.cols && cell_y == 0;
    }
    screen >= 0
        && screen < snapshot.screens
        && cell_x >= 0
        && cell_x < snapshot.cols
        && cell_y >= 0
        && cell_y < snapshot.rows
}

fn first_empty(items: &[WorkspaceItem], screen: i32, cols: i32, rows: i32) -> Option<(i32, i32)> {
    let taken: Vec<(i32, i32)> = items
        .iter()
        .filter(|item| item.screen == screen)
        .map(|item| (item.cell_x, item.cell_y))
        .collect();
    for y in 0..rows {
        for x in 0..cols {
            if !taken.contains(&(x, y)) {
                return Some((x, y));
            }
        }
    }
    None
}

fn place(
    items: &mut Vec<WorkspaceItem>,
    snapshot: &WorkspaceSnapshot,
    package: &str,
    class: &str,
    profile_id: u64,
    dest: (i32, i32, i32),
) -> Result<(), WorkspaceError> {
    let (screen, cell_x, cell_y) = dest;
    require_id(package, class)?;
    if !in_bounds(snapshot, screen, cell_x, cell_y) {
        return Err(WorkspaceError::OutOfBounds);
    }
    if items.iter().any(|item| {
        item.screen == screen
            && item.cell_x == cell_x
            && item.cell_y == cell_y
            && !item.same_app(package, class, profile_id)
    }) {
        return Err(WorkspaceError::Occupied);
    }
    if let Some(existing) = items
        .iter_mut()
        .find(|item| item.same_app(package, class, profile_id))
    {
        existing.screen = screen;
        existing.cell_x = cell_x;
        existing.cell_y = cell_y;
        return Ok(());
    }
    items.push(WorkspaceItem {
        package: package.to_string(),
        class: class.to_string(),
        profile_id,
        screen,
        cell_x,
        cell_y,
    });
    Ok(())
}

fn dock(
    items: &mut Vec<WorkspaceItem>,
    snapshot: &WorkspaceSnapshot,
    package: &str,
    class: &str,
    profile_id: u64,
) -> Result<(), WorkspaceError> {
    require_id(package, class)?;
    if items
        .iter()
        .any(|item| item.same_app(package, class, profile_id) && item.screen == HOTSEAT)
    {
        return Ok(());
    }
    let cell_x = (0..snapshot.cols).find(|&x| {
        !items
            .iter()
            .any(|item| item.screen == HOTSEAT && item.cell_x == x)
    });
    let Some(cell_x) = cell_x else {
        return Err(WorkspaceError::Full);
    };
    items.retain(|item| !item.same_app(package, class, profile_id));
    items.push(WorkspaceItem {
        package: package.to_string(),
        class: class.to_string(),
        profile_id,
        screen: HOTSEAT,
        cell_x,
        cell_y: 0,
    });
    Ok(())
}

fn pin(
    items: &mut Vec<WorkspaceItem>,
    snapshot: &WorkspaceSnapshot,
    package: &str,
    class: &str,
    profile_id: u64,
    preferred_screen: i32,
) -> Result<(), WorkspaceError> {
    require_id(package, class)?;
    if items
        .iter()
        .any(|item| item.same_app(package, class, profile_id))
    {
        return Ok(());
    }
    let start = preferred_screen.clamp(0, snapshot.screens - 1);
    for i in 0..snapshot.screens {
        let screen = (start + i) % snapshot.screens;
        if let Some((cell_x, cell_y)) = first_empty(items, screen, snapshot.cols, snapshot.rows) {
            items.push(WorkspaceItem {
                package: package.to_string(),
                class: class.to_string(),
                profile_id,
                screen,
                cell_x,
                cell_y,
            });
            return Ok(());
        }
    }
    Err(WorkspaceError::Full)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn item(package: &str, screen: i32, x: i32, y: i32) -> WorkspaceItem {
        WorkspaceItem {
            package: package.to_string(),
            class: "Main".to_string(),
            profile_id: 0,
            screen,
            cell_x: x,
            cell_y: y,
        }
    }

    fn snap(items: Vec<WorkspaceItem>) -> WorkspaceSnapshot {
        WorkspaceSnapshot {
            items,
            cols: 2,
            rows: 2,
            screens: SCREENS,
        }
    }

    fn apply(items: Vec<WorkspaceItem>, command: WorkspaceCommand) -> Vec<WorkspaceItem> {
        apply_workspace_command(snap(items), command).unwrap().items
    }

    #[test]
    fn pin_fills_row_major_then_second_page() {
        let mut items = Vec::new();
        for name in ["a", "b", "c", "d", "e"] {
            items = apply(
                items,
                WorkspaceCommand::Pin {
                    package: name.to_string(),
                    class: "Main".to_string(),
                    profile_id: 0,
                    preferred_screen: 0,
                },
            );
        }
        assert_eq!(items[0].package, "a");
        assert_eq!((items[1].cell_x, items[1].cell_y), (1, 0));
        assert_eq!(items.last().unwrap().screen, 1);
    }

    #[test]
    fn full_pin_is_rejected() {
        let mut items = Vec::new();
        for i in 0..8 {
            items = apply(
                items,
                WorkspaceCommand::Pin {
                    package: format!("p{i}"),
                    class: "Main".to_string(),
                    profile_id: 0,
                    preferred_screen: 0,
                },
            );
        }
        let err = apply_workspace_command(
            snap(items),
            WorkspaceCommand::Pin {
                package: "overflow".to_string(),
                class: "Main".to_string(),
                profile_id: 0,
                preferred_screen: 0,
            },
        )
        .unwrap_err();
        assert_eq!(err, WorkspaceError::Full);
    }

    #[test]
    fn dock_moves_off_workspace_and_full_dock_keeps_pin() {
        let pinned = apply(
            vec![],
            WorkspaceCommand::Pin {
                package: "docked".to_string(),
                class: "Main".to_string(),
                profile_id: 0,
                preferred_screen: 0,
            },
        );
        let docked = apply(
            pinned,
            WorkspaceCommand::Dock {
                package: "docked".to_string(),
                class: "Main".to_string(),
                profile_id: 0,
            },
        );
        assert_eq!(docked[0].screen, HOTSEAT);
        let mut full = docked;
        full = apply(
            full,
            WorkspaceCommand::Dock {
                package: "other".to_string(),
                class: "Main".to_string(),
                profile_id: 0,
            },
        );
        let extra = apply(
            full.clone(),
            WorkspaceCommand::Pin {
                package: "extra".to_string(),
                class: "Main".to_string(),
                profile_id: 0,
                preferred_screen: 0,
            },
        );
        let err = apply_workspace_command(
            snap(extra.clone()),
            WorkspaceCommand::Dock {
                package: "extra".to_string(),
                class: "Main".to_string(),
                profile_id: 0,
            },
        )
        .unwrap_err();
        assert_eq!(err, WorkspaceError::Full);
        assert_eq!(extra.last().unwrap().screen, 0);
    }

    #[test]
    fn place_rejects_occupied_and_out_of_bounds() {
        let items = vec![item("a", 0, 0, 0), item("b", 0, 1, 0)];
        let occupied = apply_workspace_command(
            snap(items.clone()),
            WorkspaceCommand::Place {
                package: "a".to_string(),
                class: "Main".to_string(),
                profile_id: 0,
                screen: 0,
                cell_x: 1,
                cell_y: 0,
            },
        )
        .unwrap_err();
        assert_eq!(occupied, WorkspaceError::Occupied);
        let oob = apply_workspace_command(
            snap(items),
            WorkspaceCommand::Place {
                package: "a".to_string(),
                class: "Main".to_string(),
                profile_id: 0,
                screen: 0,
                cell_x: 4,
                cell_y: 4,
            },
        )
        .unwrap_err();
        assert_eq!(oob, WorkspaceError::OutOfBounds);
    }

    #[test]
    fn drop_missing_and_remove() {
        let items = vec![item("keep", 0, 0, 0), item("gone", 0, 1, 0)];
        let kept = apply(
            items,
            WorkspaceCommand::DropMissing {
                live: vec![("keep".into(), "Main".into(), 0)],
            },
        );
        assert_eq!(kept.len(), 1);
        let empty = apply(
            kept,
            WorkspaceCommand::Remove {
                package: "keep".into(),
                class: "Main".into(),
                profile_id: 0,
            },
        );
        assert!(empty.is_empty());
    }

    #[test]
    fn empty_component_is_invalid() {
        let err = apply_workspace_command(
            snap(vec![]),
            WorkspaceCommand::Pin {
                package: String::new(),
                class: "Main".into(),
                profile_id: 0,
                preferred_screen: 0,
            },
        )
        .unwrap_err();
        assert_eq!(err, WorkspaceError::InvalidProfile);
    }
}
