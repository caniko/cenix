//! Strict backup artifact codec.
//!
//! Byte-exact port of the Kotlin `BackupJsonCodec`: canonical JSON encoding,
//! bounded strict decoding with duplicate-key rejection, envelope checksum
//! verification, v1 import defaults, and the restore-journal format. The golden
//! fixtures under `android/app/src/test/resources/backup` pin wire compatibility
//! on both sides.
//!
//! String escaping mirrors Android's `JsonWriter` exactly: short `\t\b\n\r\f`
//! forms, `\u2028`/`\u2029` escapes, and `\u00XX` for other controls; printable
//! characters (including `<>&='`) pass through literally. Envelope source
//! strings use the minimal `quote()` escaping. Any deviation breaks the
//! `valid-v2.json` golden test.

use serde::Deserialize as _;
use serde::de::Error as _;

use super::ProfileKind;
use super::backup::{
    BACKUP_FORMAT_VERSION, BackupDocument, BackupProfileRef, BackupSettings, BackupWidgetMetadata,
    CategoryAssignment, DrawerBackup, DrawerCategory, DrawerTaxonomy, IconOverride,
};
use super::workspace::{
    CellRect, ComponentId, ContainerRef, Folder, FolderMember, GridSpec, ItemPayload, ShortcutId,
    WidgetProviderId, WorkspaceItem, WorkspacePage, WorkspaceSnapshot,
};

pub const CODEC_FORMAT: &str = "com.caniko.cenix.backup";
pub const CODEC_VERSION: u32 = 2;
pub const CODEC_VERSION_V1: u32 = 1;
pub const MAX_BYTES: usize = 1024 * 1024;
pub const MAX_DEPTH: usize = 8;
pub const MAX_PAGES: usize = 64;
pub const MAX_ITEMS: usize = 4096;
pub const MAX_FOLDERS: usize = 512;
pub const MAX_MEMBERS: usize = 256;
pub const MAX_WIDGETS: usize = 512;
pub const MAX_PROFILES: usize = 2;
pub const MAX_STRING: usize = 256;
pub use crate::drawer::{
    MAX_CUSTOM_CATEGORIES as MAX_DRAWER_CATEGORIES, MAX_ORDER_ENTRIES as MAX_DRAWER_ORDER,
};
pub const MAX_DRAWER_ROWS: usize = 10_000;
pub const JOURNAL_VERSION: i64 = 1;

const FORBIDDEN: &[&str] = &[
    "generation",
    "appWidgetId",
    "label",
    "labels",
    "icon",
    "icons",
    "intent",
    "intents",
    "uri",
    "url",
    "notification",
    "notifications",
    "wallpaper",
    "diagnostics",
];

/// Machine-readable failure reasons. `Display` yields the exact reason string
/// the Kotlin codec reports, so both sides classify failures identically.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CodecError {
    Format,
    Version,
    Source,
    Payload,
    Checksum,
    Trailing,
    Malformed,
    Drawer,
    Journal,
    Obsolete,
    Settings,
    Profiles,
    Workspace,
    Widgets,
    Allocator,
    Grid,
    Pages,
    Items,
    Folders,
    Item,
    Folder,
    Member,
    Widget,
    Kind,
    Container,
    Cell,
    Page,
    String,
    Integer,
    Count,
    DuplicateKey,
    Forbidden,
    Token,
    Truncated,
    Depth,
    Overlap,
    Span,
    Profile,
    Mixed,
    Duplicate,
    Utf8,
    Oversized,
}

impl CodecError {
    pub fn reason(self) -> &'static str {
        match self {
            Self::Format => "format",
            Self::Version => "version",
            Self::Source => "source",
            Self::Payload => "payload",
            Self::Checksum => "checksum",
            Self::Trailing => "trailing",
            Self::Malformed => "malformed",
            Self::Drawer => "drawer",
            Self::Journal => "journal",
            Self::Obsolete => "obsolete",
            Self::Settings => "settings",
            Self::Profiles => "profiles",
            Self::Workspace => "workspace",
            Self::Widgets => "widgets",
            Self::Allocator => "allocator",
            Self::Grid => "grid",
            Self::Pages => "pages",
            Self::Items => "items",
            Self::Folders => "folders",
            Self::Item => "item",
            Self::Folder => "folder",
            Self::Member => "member",
            Self::Widget => "widget",
            Self::Kind => "kind",
            Self::Container => "container",
            Self::Cell => "cell",
            Self::Page => "page",
            Self::String => "string",
            Self::Integer => "integer",
            Self::Count => "count",
            Self::DuplicateKey => "duplicate-key",
            Self::Forbidden => "forbidden",
            Self::Token => "token",
            Self::Truncated => "truncated",
            Self::Depth => "depth",
            Self::Overlap => "overlap",
            Self::Span => "span",
            Self::Profile => "profile",
            Self::Mixed => "mixed",
            Self::Duplicate => "duplicate",
            Self::Utf8 => "utf8",
            Self::Oversized => "oversized",
        }
    }
}

impl std::fmt::Display for CodecError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.reason())
    }
}

impl std::error::Error for CodecError {}

// ---------------------------------------------------------------------------
// Strict JSON value model
// ---------------------------------------------------------------------------
//
// Tokenizing, number/string grammar, UTF-8, and escape handling come from
// serde_json. What stays Cenix-specific: duplicate-key rejection, insertion
// order preservation, strict integer validation, depth accounting during the
// typed walk, and exact error reasons.

#[derive(Debug, Clone, PartialEq)]
enum SVal {
    Null,
    Bool(bool),
    Number(serde_json::Number),
    Str(String),
    Array(Vec<SVal>),
    Object(Vec<(String, SVal)>),
}

struct SValVisitor;

impl<'de> serde::de::Visitor<'de> for SValVisitor {
    type Value = SVal;

    fn expecting(&self, formatter: &mut std::fmt::Formatter) -> std::fmt::Result {
        formatter.write_str("any JSON value")
    }

    fn visit_bool<E: serde::de::Error>(self, value: bool) -> Result<SVal, E> {
        Ok(SVal::Bool(value))
    }

    fn visit_i64<E: serde::de::Error>(self, value: i64) -> Result<SVal, E> {
        Ok(SVal::Number(value.into()))
    }

    fn visit_u64<E: serde::de::Error>(self, value: u64) -> Result<SVal, E> {
        Ok(SVal::Number(value.into()))
    }

    fn visit_f64<E: serde::de::Error>(self, value: f64) -> Result<SVal, E> {
        serde_json::Number::from_f64(value)
            .map(SVal::Number)
            .ok_or_else(|| E::custom("non-finite number"))
    }

    fn visit_str<E: serde::de::Error>(self, value: &str) -> Result<SVal, E> {
        Ok(SVal::Str(value.to_owned()))
    }

    fn visit_string<E: serde::de::Error>(self, value: String) -> Result<SVal, E> {
        Ok(SVal::Str(value))
    }

    fn visit_none<E: serde::de::Error>(self) -> Result<SVal, E> {
        Ok(SVal::Null)
    }

    fn visit_unit<E: serde::de::Error>(self) -> Result<SVal, E> {
        Ok(SVal::Null)
    }

    fn visit_seq<A: serde::de::SeqAccess<'de>>(self, mut seq: A) -> Result<SVal, A::Error> {
        let mut items = Vec::new();
        while let Some(item) = seq.next_element()? {
            items.push(item);
        }
        Ok(SVal::Array(items))
    }

    fn visit_map<A: serde::de::MapAccess<'de>>(self, mut map: A) -> Result<SVal, A::Error> {
        let mut members = Vec::new();
        while let Some(key) = map.next_key::<String>()? {
            if members.iter().any(|(k, _): &(String, SVal)| k == &key) {
                return Err(A::Error::custom("cenix-duplicate-key"));
            }
            members.push((key, map.next_value()?));
        }
        Ok(SVal::Object(members))
    }
}

impl<'de> serde::Deserialize<'de> for SVal {
    fn deserialize<D: serde::Deserializer<'de>>(deserializer: D) -> Result<SVal, D::Error> {
        deserializer.deserialize_any(SValVisitor)
    }
}

fn classify_syntax_error(error: serde_json::Error) -> CodecError {
    // serde_json reports our visitor's duplicate rejection as a data error
    // carrying the marker; recursion exhaustion arrives as a syntax error.
    let message = error.to_string();
    if message.contains("cenix-duplicate-key") {
        CodecError::DuplicateKey
    } else if error.is_eof() {
        CodecError::Truncated
    } else if message.contains("recursion limit exceeded") {
        CodecError::Depth
    } else {
        CodecError::Malformed
    }
}

fn parse_document(text: &str) -> Result<SVal, CodecError> {
    let mut deserializer = serde_json::Deserializer::from_slice(text.as_bytes());
    let value = SVal::deserialize(&mut deserializer).map_err(classify_syntax_error)?;
    deserializer.end().map_err(|_| CodecError::Trailing)?;
    Ok(value)
}

// ---------------------------------------------------------------------------
// Typed accessors over SVal
// ---------------------------------------------------------------------------

fn as_object(value: &SVal) -> Result<&[(String, SVal)], CodecError> {
    match value {
        SVal::Object(members) => Ok(members),
        _ => Err(CodecError::Malformed),
    }
}

fn skip_forbidden(key: &str) -> Result<(), CodecError> {
    if FORBIDDEN.contains(&key) {
        return Err(CodecError::Forbidden);
    }
    Ok(())
}

fn bounded_string(value: &SVal) -> Result<String, CodecError> {
    match value {
        SVal::Str(s) => {
            if s.chars().count() > MAX_STRING || s.chars().any(char::is_control) {
                return Err(CodecError::String);
            }
            Ok(s.clone())
        }
        _ => Err(CodecError::String),
    }
}

/// Strict integer fields: serde_json already enforces the `-?(0|[1-9][0-9]*)`
/// grammar (leading zeros, `+`, fractions, and exponents never reach an
/// integer visitor), so only the i64 range check remains here.
fn exact_long(value: &SVal) -> Result<i64, CodecError> {
    match value {
        SVal::Number(number) => number.as_i64().ok_or(CodecError::Integer),
        _ => Err(CodecError::Integer),
    }
}

fn exact_int(value: &SVal) -> Result<i32, CodecError> {
    let value = exact_long(value)?;
    i32::try_from(value).map_err(|_| CodecError::Integer)
}

/// Mirrors the writer bound: encodable ids are below `Long.MAX_VALUE`.
fn exact_u64(value: &SVal) -> Result<u64, CodecError> {
    let value = exact_long(value)?;
    if value < 0 || value == i64::MAX {
        return Err(CodecError::Integer);
    }
    Ok(value as u64)
}

fn read_list<T>(
    value: &SVal,
    max: usize,
    error: CodecError,
    read_one: impl Fn(&SVal) -> Result<T, CodecError>,
) -> Result<Vec<T>, CodecError> {
    match value {
        SVal::Array(items) => {
            if items.len() > max {
                return Err(CodecError::Count);
            }
            items.iter().map(read_one).collect()
        }
        _ => Err(error),
    }
}

// ---------------------------------------------------------------------------
// Domain decoders
// ---------------------------------------------------------------------------

fn read_settings(value: &SVal) -> Result<BackupSettings, CodecError> {
    let members = as_object(value).map_err(|_| CodecError::Settings)?;
    let mut grid: Option<String> = None;
    let mut dots: Option<bool> = None;
    let mut themed: Option<bool> = None;
    let mut auto: Option<bool> = None;
    for (key, val) in members {
        match key.as_str() {
            "gridName" => grid = Some(bounded_string(val)?),
            "notificationDots" => {
                dots = Some(match val {
                    SVal::Bool(b) => *b,
                    _ => return Err(CodecError::Settings),
                })
            }
            "themedIcons" => {
                themed = Some(match val {
                    SVal::Bool(b) => *b,
                    _ => return Err(CodecError::Settings),
                })
            }
            "autoAddApps" => {
                auto = Some(match val {
                    SVal::Bool(b) => *b,
                    _ => return Err(CodecError::Settings),
                })
            }
            other => skip_forbidden(other)?,
        }
    }
    Ok(BackupSettings {
        grid_name: grid.ok_or(CodecError::Settings)?,
        notification_dots: dots.ok_or(CodecError::Settings)?,
        themed_icons: themed.ok_or(CodecError::Settings)?,
        auto_add_apps: auto.ok_or(CodecError::Settings)?,
    })
}

fn read_profile(value: &SVal) -> Result<BackupProfileRef, CodecError> {
    let members = as_object(value).map_err(|_| CodecError::Profile)?;
    let mut id: Option<u64> = None;
    let mut kind: Option<ProfileKind> = None;
    for (key, val) in members {
        match key.as_str() {
            "profileId" => id = Some(exact_u64(val)?),
            "kind" => {
                kind = Some(match bounded_string(val)?.as_str() {
                    "personal" => ProfileKind::Personal,
                    "work" => ProfileKind::Work,
                    "private" => ProfileKind::Private,
                    _ => return Err(CodecError::Profile),
                })
            }
            other => skip_forbidden(other)?,
        }
    }
    Ok(BackupProfileRef {
        profile_id: id.ok_or(CodecError::Profile)?,
        kind: kind.ok_or(CodecError::Profile)?,
    })
}

fn read_grid(value: &SVal) -> Result<GridSpec, CodecError> {
    let members = as_object(value).map_err(|_| CodecError::Grid)?;
    let mut cols: Option<i32> = None;
    let mut rows: Option<i32> = None;
    let mut hotseat: Option<i32> = None;
    for (key, val) in members {
        match key.as_str() {
            "cols" => cols = Some(exact_int(val)?),
            "rows" => rows = Some(exact_int(val)?),
            "hotseatCols" => hotseat = Some(exact_int(val)?),
            other => skip_forbidden(other)?,
        }
    }
    Ok(GridSpec {
        cols: cols.ok_or(CodecError::Grid)?,
        rows: rows.ok_or(CodecError::Grid)?,
        hotseat_cols: hotseat.ok_or(CodecError::Grid)?,
    })
}

fn read_page(value: &SVal) -> Result<WorkspacePage, CodecError> {
    let members = as_object(value).map_err(|_| CodecError::Page)?;
    let mut id: Option<u64> = None;
    let mut rank: Option<i32> = None;
    for (key, val) in members {
        match key.as_str() {
            "pageId" => id = Some(exact_u64(val)?),
            "rank" => rank = Some(exact_int(val)?),
            other => skip_forbidden(other)?,
        }
    }
    Ok(WorkspacePage {
        page_id: id.ok_or(CodecError::Page)?,
        rank: rank.ok_or(CodecError::Page)?,
    })
}

fn read_component(package: String, class: String, profile_id: u64) -> ComponentId {
    ComponentId {
        package,
        class,
        profile_id,
    }
}

fn read_payload_kind(value: &SVal) -> Result<ItemPayload, CodecError> {
    let members = as_object(value).map_err(|_| CodecError::Kind)?;
    let mut kind: Option<String> = None;
    let mut pkg: Option<String> = None;
    let mut cls: Option<String> = None;
    let mut shortcut: Option<String> = None;
    let mut profile: Option<u64> = None;
    for (key, val) in members {
        match key.as_str() {
            "kind" => kind = Some(bounded_string(val)?),
            "package" => pkg = Some(bounded_string(val)?),
            "class" => cls = Some(bounded_string(val)?),
            "shortcutId" => shortcut = Some(bounded_string(val)?),
            "profileId" => profile = Some(exact_u64(val)?),
            other => skip_forbidden(other)?,
        }
    }
    match kind.as_deref() {
        Some("application") => Ok(ItemPayload::Application(read_component(
            pkg.ok_or(CodecError::Kind)?,
            cls.ok_or(CodecError::Kind)?,
            profile.ok_or(CodecError::Kind)?,
        ))),
        Some("folder") => Ok(ItemPayload::Folder),
        Some("shortcut") => Ok(ItemPayload::Shortcut(ShortcutId {
            package: pkg.ok_or(CodecError::Kind)?,
            shortcut_id: shortcut.ok_or(CodecError::Kind)?,
            profile_id: profile.ok_or(CodecError::Kind)?,
        })),
        Some("widget") => Ok(ItemPayload::Widget(WidgetProviderId {
            package: pkg.ok_or(CodecError::Kind)?,
            class: cls.ok_or(CodecError::Kind)?,
            profile_id: profile.ok_or(CodecError::Kind)?,
        })),
        _ => Err(CodecError::Kind),
    }
}

fn read_container(value: &SVal) -> Result<ContainerRef, CodecError> {
    let members = as_object(value).map_err(|_| CodecError::Container)?;
    let mut kind: Option<String> = None;
    let mut page: Option<u64> = None;
    for (key, val) in members {
        match key.as_str() {
            "kind" => kind = Some(bounded_string(val)?),
            "pageId" => page = Some(exact_u64(val)?),
            other => skip_forbidden(other)?,
        }
    }
    match kind.as_deref() {
        Some("workspace") => Ok(ContainerRef::Workspace {
            page_id: page.ok_or(CodecError::Container)?,
        }),
        Some("hotseat") => Ok(ContainerRef::Hotseat),
        _ => Err(CodecError::Container),
    }
}

fn read_cell(value: &SVal) -> Result<CellRect, CodecError> {
    let members = as_object(value).map_err(|_| CodecError::Cell)?;
    let mut x: Option<i32> = None;
    let mut y: Option<i32> = None;
    let mut sx: Option<i32> = None;
    let mut sy: Option<i32> = None;
    for (key, val) in members {
        match key.as_str() {
            "cellX" => x = Some(exact_int(val)?),
            "cellY" => y = Some(exact_int(val)?),
            "spanX" => sx = Some(exact_int(val)?),
            "spanY" => sy = Some(exact_int(val)?),
            other => skip_forbidden(other)?,
        }
    }
    Ok(CellRect {
        cell_x: x.ok_or(CodecError::Cell)?,
        cell_y: y.ok_or(CodecError::Cell)?,
        span_x: sx.ok_or(CodecError::Cell)?,
        span_y: sy.ok_or(CodecError::Cell)?,
    })
}

fn read_item(value: &SVal) -> Result<WorkspaceItem, CodecError> {
    let members = as_object(value).map_err(|_| CodecError::Item)?;
    let mut id: Option<u64> = None;
    let mut payload: Option<ItemPayload> = None;
    let mut container: Option<ContainerRef> = None;
    let mut cell: Option<CellRect> = None;
    for (key, val) in members {
        match key.as_str() {
            "itemId" => id = Some(exact_u64(val)?),
            "payload" => payload = Some(read_payload_kind(val)?),
            "container" => container = Some(read_container(val)?),
            "cell" => cell = Some(read_cell(val)?),
            other => skip_forbidden(other)?,
        }
    }
    Ok(WorkspaceItem {
        item_id: id.ok_or(CodecError::Item)?,
        payload: payload.ok_or(CodecError::Item)?,
        container: container.ok_or(CodecError::Item)?,
        cell: cell.ok_or(CodecError::Item)?,
    })
}

fn read_member(value: &SVal) -> Result<FolderMember, CodecError> {
    let members = as_object(value).map_err(|_| CodecError::Member)?;
    let mut id: Option<u64> = None;
    let mut payload: Option<ItemPayload> = None;
    let mut rank: Option<u32> = None;
    for (key, val) in members {
        match key.as_str() {
            "itemId" => id = Some(exact_u64(val)?),
            "payload" => payload = Some(read_payload_kind(val)?),
            "rank" => {
                let rank_value = exact_u64(val)?;
                if rank_value > u32::MAX as u64 {
                    return Err(CodecError::Integer);
                }
                rank = Some(rank_value as u32);
            }
            other => skip_forbidden(other)?,
        }
    }
    Ok(FolderMember {
        item_id: id.ok_or(CodecError::Member)?,
        payload: payload.ok_or(CodecError::Member)?,
        rank: rank.ok_or(CodecError::Member)?,
    })
}

fn read_folder(value: &SVal) -> Result<Folder, CodecError> {
    let members = as_object(value).map_err(|_| CodecError::Folder)?;
    let mut id: Option<u64> = None;
    let mut title: Option<String> = None;
    let mut folder_members: Option<Vec<FolderMember>> = None;
    for (key, val) in members {
        match key.as_str() {
            "folderId" => id = Some(exact_u64(val)?),
            "title" => title = Some(bounded_string(val)?),
            "members" => {
                folder_members = Some(read_list(
                    val,
                    MAX_MEMBERS,
                    CodecError::Folder,
                    read_member,
                )?)
            }
            other => skip_forbidden(other)?,
        }
    }
    Ok(Folder {
        folder_id: id.ok_or(CodecError::Folder)?,
        title: title.ok_or(CodecError::Folder)?,
        members: folder_members.ok_or(CodecError::Folder)?,
    })
}

fn read_widget(value: &SVal) -> Result<BackupWidgetMetadata, CodecError> {
    let members = as_object(value).map_err(|_| CodecError::Widget)?;
    let mut id: Option<u64> = None;
    let mut min_x: Option<i32> = None;
    let mut min_y: Option<i32> = None;
    let mut resize_x: Option<i32> = None;
    let mut resize_y: Option<i32> = None;
    for (key, val) in members {
        match key.as_str() {
            "itemId" => id = Some(exact_u64(val)?),
            "minSpanX" => min_x = Some(exact_int(val)?),
            "minSpanY" => min_y = Some(exact_int(val)?),
            "resizeX" => resize_x = Some(exact_int(val)?),
            "resizeY" => resize_y = Some(exact_int(val)?),
            other => skip_forbidden(other)?,
        }
    }
    Ok(BackupWidgetMetadata {
        item_id: id.ok_or(CodecError::Widget)?,
        min_span_x: min_x.ok_or(CodecError::Widget)?,
        min_span_y: min_y.ok_or(CodecError::Widget)?,
        resize_x: resize_x.ok_or(CodecError::Widget)?,
        resize_y: resize_y.ok_or(CodecError::Widget)?,
    })
}

fn read_workspace(value: &SVal) -> Result<WorkspaceSnapshot, CodecError> {
    let members = as_object(value).map_err(|_| CodecError::Workspace)?;
    let mut grid: Option<GridSpec> = None;
    let mut pages: Option<Vec<WorkspacePage>> = None;
    let mut items: Option<Vec<WorkspaceItem>> = None;
    let mut folders: Option<Vec<Folder>> = None;
    for (key, val) in members {
        match key.as_str() {
            "grid" => grid = Some(read_grid(val)?),
            "pages" => pages = Some(read_list(val, MAX_PAGES, CodecError::Pages, read_page)?),
            "items" => items = Some(read_list(val, MAX_ITEMS, CodecError::Items, read_item)?),
            "folders" => {
                folders = Some(read_list(
                    val,
                    MAX_FOLDERS,
                    CodecError::Folders,
                    read_folder,
                )?)
            }
            other => skip_forbidden(other)?,
        }
    }
    Ok(WorkspaceSnapshot {
        generation: 0,
        grid: grid.ok_or(CodecError::Workspace)?,
        pages: pages.ok_or(CodecError::Pages)?,
        items: items.ok_or(CodecError::Items)?,
        folders: folders.ok_or(CodecError::Folders)?,
    })
}

fn read_drawer_category(value: &SVal) -> Result<DrawerCategory, CodecError> {
    let members = as_object(value).map_err(|_| CodecError::Drawer)?;
    let mut id: Option<String> = None;
    let mut title: Option<String> = None;
    for (key, val) in members {
        match key.as_str() {
            "id" => id = Some(bounded_string(val)?),
            "title" => title = Some(bounded_string(val)?),
            other => skip_forbidden(other)?,
        }
    }
    Ok(DrawerCategory {
        id: id.ok_or(CodecError::Drawer)?,
        title: title.ok_or(CodecError::Drawer)?,
    })
}

fn read_assignment(value: &SVal) -> Result<CategoryAssignment, CodecError> {
    let members = as_object(value).map_err(|_| CodecError::Drawer)?;
    let mut package: Option<String> = None;
    let mut profile: Option<u64> = None;
    let mut category: Option<String> = None;
    for (key, val) in members {
        match key.as_str() {
            "package" => package = Some(bounded_string(val)?),
            "profileId" => profile = Some(exact_u64(val)?),
            "category" => category = Some(bounded_string(val)?),
            other => skip_forbidden(other)?,
        }
    }
    Ok(CategoryAssignment {
        package: package.ok_or(CodecError::Drawer)?,
        profile_id: profile.ok_or(CodecError::Drawer)?,
        category_id: category.ok_or(CodecError::Drawer)?,
    })
}

fn read_icon_override(value: &SVal) -> Result<IconOverride, CodecError> {
    let members = as_object(value).map_err(|_| CodecError::Drawer)?;
    let mut package: Option<String> = None;
    let mut class: Option<String> = None;
    let mut profile: Option<u64> = None;
    let mut pack: Option<String> = None;
    let mut drawable: Option<String> = None;
    for (key, val) in members {
        match key.as_str() {
            "package" => package = Some(bounded_string(val)?),
            "class" => class = Some(bounded_string(val)?),
            "profileId" => profile = Some(exact_u64(val)?),
            "pack" => pack = Some(bounded_string(val)?),
            "drawable" => drawable = Some(bounded_string(val)?),
            other => skip_forbidden(other)?,
        }
    }
    Ok(IconOverride {
        package: package.ok_or(CodecError::Drawer)?,
        class: class.ok_or(CodecError::Drawer)?,
        profile_id: profile.ok_or(CodecError::Drawer)?,
        pack_package: pack.ok_or(CodecError::Drawer)?,
        drawable: drawable.ok_or(CodecError::Drawer)?,
    })
}

fn read_taxonomy(value: &SVal) -> Result<DrawerTaxonomy, CodecError> {
    let members = as_object(value).map_err(|_| CodecError::Drawer)?;
    let mut selected: Option<String> = None;
    let mut categories: Option<Vec<DrawerCategory>> = None;
    let mut order: Option<Vec<String>> = None;
    for (key, val) in members {
        match key.as_str() {
            "selected" => selected = Some(bounded_string(val)?),
            "categories" => {
                categories = Some(read_list(
                    val,
                    MAX_DRAWER_CATEGORIES,
                    CodecError::Drawer,
                    read_drawer_category,
                )?)
            }
            "order" => {
                order = Some(read_list(
                    val,
                    MAX_DRAWER_ORDER,
                    CodecError::Drawer,
                    bounded_string,
                )?)
            }
            other => skip_forbidden(other)?,
        }
    }
    Ok(DrawerTaxonomy {
        selected: selected.ok_or(CodecError::Drawer)?,
        categories: categories.ok_or(CodecError::Drawer)?,
        order: order.ok_or(CodecError::Drawer)?,
    })
}

fn read_drawer(value: &SVal, is_v1: bool) -> Result<Option<DrawerBackup>, CodecError> {
    // v1 envelopes predate drawer customization: content is skipped (with the
    // usual forbidden-field checks) and empty defaults apply.
    if is_v1 {
        if let SVal::Object(members) = value {
            for (key, _) in members {
                skip_forbidden(key)?;
            }
            skip_value_shape(value)?;
        }
        return Ok(None);
    }
    let members = as_object(value).map_err(|_| CodecError::Drawer)?;
    let mut mode: Option<String> = None;
    let mut personal: Option<DrawerTaxonomy> = None;
    let mut work: Option<DrawerTaxonomy> = None;
    let mut saw_work = false;
    let mut assignments: Option<Vec<CategoryAssignment>> = None;
    let mut pack: Option<String> = None;
    let mut overrides: Option<Vec<IconOverride>> = None;
    for (key, val) in members {
        match key.as_str() {
            "mode" => mode = Some(bounded_string(val)?),
            "personal" => personal = Some(read_taxonomy(val)?),
            "work" => {
                saw_work = true;
                work = match val {
                    SVal::Null => None,
                    _ => Some(read_taxonomy(val)?),
                };
            }
            "assignments" => {
                assignments = Some(read_list(
                    val,
                    MAX_DRAWER_ROWS,
                    CodecError::Drawer,
                    read_assignment,
                )?)
            }
            "pack" => pack = Some(bounded_string(val)?),
            "overrides" => {
                overrides = Some(read_list(
                    val,
                    MAX_DRAWER_ROWS,
                    CodecError::Drawer,
                    read_icon_override,
                )?)
            }
            other => skip_forbidden(other)?,
        }
    }
    if !saw_work {
        return Err(CodecError::Drawer);
    }
    let mode = mode.ok_or(CodecError::Drawer)?;
    if mode != "sections" && mode != "all" {
        return Err(CodecError::Drawer);
    }
    Ok(Some(DrawerBackup {
        mode,
        personal: personal.ok_or(CodecError::Drawer)?,
        work,
        assignments: assignments.ok_or(CodecError::Drawer)?,
        icon_pack: pack.ok_or(CodecError::Drawer)?,
        icon_overrides: overrides.ok_or(CodecError::Drawer)?,
    }))
}

/// Structural skip with depth accounting, mirroring `skip()` + forbidden checks.
fn skip_value_shape(value: &SVal) -> Result<(), CodecError> {
    fn walk(value: &SVal, depth: usize) -> Result<(), CodecError> {
        if depth > MAX_DEPTH {
            return Err(CodecError::Depth);
        }
        match value {
            SVal::Object(members) => {
                for (key, val) in members {
                    skip_forbidden(key)?;
                    walk(val, depth + 1)?;
                }
                Ok(())
            }
            SVal::Array(items) => {
                for item in items {
                    walk(item, depth + 1)?;
                }
                Ok(())
            }
            _ => Ok(()),
        }
    }
    walk(value, 1)
}

// ---------------------------------------------------------------------------
// Envelope decode
// ---------------------------------------------------------------------------

/// Locate the top-level `"payload"` object slice, mirroring `payloadSlice()`
/// exactly (string-aware scan, depth guard, `{` requirement).
fn payload_slice(text: &str) -> Result<(usize, usize), CodecError> {
    let bytes = text.as_bytes();
    let mut i = 0;
    let mut in_string = false;
    let mut escape = false;
    let mut depth: i64 = 0;
    while i < bytes.len() {
        let c = bytes[i];
        if in_string {
            if escape {
                escape = false;
            } else if c == b'\\' {
                escape = true;
            } else if c == b'"' {
                in_string = false;
            }
            i += 1;
            continue;
        }
        if c == b'"' {
            if depth == 1 && text[i..].starts_with("\"payload\"") {
                let mut j = i + 9;
                while j < bytes.len() && bytes[j] <= b' ' {
                    j += 1;
                }
                if j < bytes.len() && bytes[j] == b':' {
                    j += 1;
                    while j < bytes.len() && bytes[j] <= b' ' {
                        j += 1;
                    }
                    if j >= bytes.len() || bytes[j] != b'{' {
                        return Err(CodecError::Payload);
                    }
                    return Ok((j, object_end(text, j)?));
                }
            }
            in_string = true;
        } else if c == b'{' || c == b'[' {
            depth += 1;
            if depth as usize > MAX_DEPTH {
                return Err(CodecError::Depth);
            }
        } else if c == b'}' || c == b']' {
            depth -= 1;
            if depth < 0 {
                return Err(CodecError::Malformed);
            }
        }
        i += 1;
    }
    Err(CodecError::Payload)
}

fn object_end(text: &str, start: usize) -> Result<usize, CodecError> {
    let bytes = text.as_bytes();
    let mut depth: i64 = 0;
    let mut i = start;
    let mut in_string = false;
    let mut escape = false;
    while i < bytes.len() {
        let c = bytes[i];
        if in_string {
            if escape {
                escape = false;
            } else if c == b'\\' {
                escape = true;
            } else if c == b'"' {
                in_string = false;
            }
        } else {
            match c {
                b'"' => in_string = true,
                b'{' | b'[' => {
                    depth += 1;
                    if depth as usize > MAX_DEPTH {
                        return Err(CodecError::Depth);
                    }
                }
                b'}' => {
                    depth -= 1;
                    if depth == 0 {
                        return Ok(i + 1);
                    }
                }
                b']' => depth -= 1,
                _ => {}
            }
        }
        i += 1;
    }
    Err(CodecError::Truncated)
}

fn sha256_hex(bytes: &[u8]) -> String {
    use sha2::{Digest, Sha256};
    let digest = Sha256::digest(bytes);
    let mut out = String::with_capacity(64);
    for byte in digest {
        out.push_str(&format!("{byte:02x}"));
    }
    out
}

/// Decode a full backup envelope. Returns the document with `format_version`
/// stamped to the current version (v1 imports upgrade with empty drawer).
pub fn decode_backup_envelope(bytes: &[u8]) -> Result<BackupDocument, CodecError> {
    if bytes.len() > MAX_BYTES {
        return Err(CodecError::Oversized);
    }
    if bytes.is_empty() {
        return Err(CodecError::Truncated);
    }
    let text = std::str::from_utf8(bytes).map_err(|_| CodecError::Utf8)?;
    let (payload_start, payload_end) = payload_slice(text)?;
    let payload_bytes = &bytes[payload_start..payload_end];
    let envelope = parse_document(text)?;
    let members = as_object(&envelope).map_err(|_| CodecError::Malformed)?;
    let mut format: Option<String> = None;
    let mut version: Option<i64> = None;
    let mut source_version: Option<String> = None;
    let mut source_commit: Option<String> = None;
    let mut sha: Option<String> = None;
    let mut saw_payload = false;
    for (key, val) in members {
        match key.as_str() {
            "format" => format = Some(bounded_string(val).map_err(|_| CodecError::Format)?),
            "version" => version = Some(exact_long(val).map_err(|_| CodecError::Version)?),
            "source" => {
                let source = as_object(val).map_err(|_| CodecError::Source)?;
                for (skey, sval) in source {
                    match skey.as_str() {
                        "version" => {
                            source_version =
                                Some(bounded_string(sval).map_err(|_| CodecError::Source)?)
                        }
                        "commit" => {
                            source_commit =
                                Some(bounded_string(sval).map_err(|_| CodecError::Source)?)
                        }
                        other => skip_forbidden(other)?,
                    }
                }
            }
            "payload" => {
                skip_value_shape(val)?;
                saw_payload = true;
            }
            "payloadSha256" => {
                sha = Some(match val {
                    SVal::Str(s) => s.clone(),
                    _ => return Err(CodecError::Checksum),
                })
            }
            other => skip_forbidden(other)?,
        }
    }
    if format.as_deref() != Some(CODEC_FORMAT) {
        return Err(CodecError::Format);
    }
    let version = version.ok_or(CodecError::Version)?;
    if version != CODEC_VERSION as i64 && version != CODEC_VERSION_V1 as i64 {
        return Err(CodecError::Version);
    }
    let source_version = source_version
        .filter(|s| !s.is_empty())
        .ok_or(CodecError::Source)?;
    let source_commit = source_commit
        .filter(|s| !s.is_empty())
        .ok_or(CodecError::Source)?;
    if !saw_payload {
        return Err(CodecError::Payload);
    }
    let checksum = sha.ok_or(CodecError::Checksum)?;
    if checksum.len() != 64
        || !checksum
            .bytes()
            .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
    {
        return Err(CodecError::Checksum);
    }
    if sha256_hex(payload_bytes) != checksum {
        return Err(CodecError::Checksum);
    }
    let payload_text = std::str::from_utf8(payload_bytes).map_err(|_| CodecError::Utf8)?;
    let payload = parse_document(payload_text)?;
    let mut document = read_payload_doc(&payload, &source_version, &source_commit, version)?;
    document.format_version = BACKUP_FORMAT_VERSION;
    validate_decoded_shape(&document)?;
    Ok(document)
}

fn item_profile(payload: &ItemPayload) -> Option<u64> {
    match payload {
        ItemPayload::Application(component) => Some(component.profile_id),
        ItemPayload::Shortcut(shortcut) => Some(shortcut.profile_id),
        ItemPayload::Widget(provider) => Some(provider.profile_id),
        ItemPayload::Folder => None,
    }
}

fn cells_overlap(
    a: &CellRect,
    b: &CellRect,
    a_container: &ContainerRef,
    b_container: &ContainerRef,
) -> bool {
    if a_container != b_container {
        return false;
    }
    let a_right = a.cell_x as i64 + a.span_x as i64;
    let b_right = b.cell_x as i64 + b.span_x as i64;
    let a_bottom = a.cell_y as i64 + a.span_y as i64;
    let b_bottom = b.cell_y as i64 + b.span_y as i64;
    (a.cell_x as i64) < b_right
        && (b.cell_x as i64) < a_right
        && (a.cell_y as i64) < b_bottom
        && (b.cell_y as i64) < a_bottom
}

/// Structural shape checks mirroring `validateShape()`: profile allowlist, id
/// uniqueness, spans, widget/folder rules, overlap. Runs after parsing and
/// before semantic validation.
pub fn validate_decoded_shape(document: &BackupDocument) -> Result<(), CodecError> {
    let mut profiles: Vec<&BackupProfileRef> = document.profiles.iter().collect();
    profiles.sort_by_key(|profile| profile.profile_id);
    let personal_first = profiles
        .first()
        .is_some_and(|profile| profile.profile_id == 0 && profile.kind == ProfileKind::Personal);
    let work_second = profiles.len() < 2
        || (profiles[1].profile_id == 1 && profiles[1].kind == ProfileKind::Work);
    if profiles.is_empty() || profiles.len() > MAX_PROFILES || !personal_first || !work_second {
        return Err(CodecError::Profile);
    }
    let allowed: std::collections::HashSet<u64> =
        profiles.iter().map(|profile| profile.profile_id).collect();
    let mut ids = std::collections::HashSet::new();
    let mut record_id = |value: u64| -> Result<(), CodecError> {
        if value == 0 || value >= i64::MAX as u64 || !ids.insert(value) {
            return Err(CodecError::Duplicate);
        }
        Ok(())
    };
    for item in &document.workspace.items {
        record_id(item.item_id)?;
        if item.cell.span_x <= 0 || item.cell.span_y <= 0 {
            return Err(CodecError::Span);
        }
        if matches!(item.payload, ItemPayload::Widget(_))
            && matches!(item.container, ContainerRef::Hotseat)
        {
            return Err(CodecError::Widget);
        }
        if let Some(profile) = item_profile(&item.payload)
            && !allowed.contains(&profile)
        {
            return Err(CodecError::Profile);
        }
    }
    for folder in &document.workspace.folders {
        let mut profile: Option<u64> = None;
        for member in &folder.members {
            record_id(member.item_id)?;
            if matches!(member.payload, ItemPayload::Widget(_) | ItemPayload::Folder) {
                return Err(CodecError::Folder);
            }
            let next = item_profile(&member.payload).ok_or(CodecError::Folder)?;
            if !allowed.contains(&next) {
                return Err(CodecError::Profile);
            }
            if let Some(previous) = profile
                && previous != next
            {
                return Err(CodecError::Mixed);
            }
            profile = Some(next);
        }
    }
    let widget_items: std::collections::HashMap<u64, &WorkspaceItem> = document
        .workspace
        .items
        .iter()
        .filter(|item| matches!(item.payload, ItemPayload::Widget(_)))
        .map(|item| (item.item_id, item))
        .collect();
    {
        let mut seen = std::collections::HashSet::new();
        for widget in &document.widgets {
            if !seen.insert(widget.item_id) {
                return Err(CodecError::Widget);
            }
        }
        if seen.len() != widget_items.len() || !seen.iter().all(|id| widget_items.contains_key(id))
        {
            return Err(CodecError::Widget);
        }
    }
    for widget in &document.widgets {
        let item = widget_items[&widget.item_id];
        if widget.min_span_x <= 0
            || widget.min_span_y <= 0
            || widget.resize_x < widget.min_span_x
            || widget.resize_y < widget.min_span_y
            || widget.min_span_x > item.cell.span_x
            || widget.min_span_y > item.cell.span_y
        {
            return Err(CodecError::Widget);
        }
    }
    for (index, left) in document.workspace.items.iter().enumerate() {
        if document.workspace.items[index + 1..]
            .iter()
            .any(|right| cells_overlap(&left.cell, &right.cell, &left.container, &right.container))
        {
            return Err(CodecError::Overlap);
        }
    }
    for assignment in &document.drawer.assignments {
        if !allowed.contains(&assignment.profile_id) {
            return Err(CodecError::Profile);
        }
    }
    for icon in &document.drawer.icon_overrides {
        if !allowed.contains(&icon.profile_id) {
            return Err(CodecError::Profile);
        }
    }
    Ok(())
}

fn encode_u64(value: u64) -> Result<i64, CodecError> {
    if value >= i64::MAX as u64 {
        return Err(CodecError::Integer);
    }
    Ok(value as i64)
}

/// Payload string escaping exactly as Android's `JsonWriter.string()`: short
/// `\t\b\n\r\f` forms, `\u2028`/`\u2029` escapes, and `\u00XX` for other
/// controls. Printable characters (including `<>&='`) pass through literally.
fn escape_payload(out: &mut String, value: &str) {
    out.push('"');
    for c in value.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\t' => out.push_str("\\t"),
            '\u{08}' => out.push_str("\\b"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\u{0C}' => out.push_str("\\f"),
            '\u{2028}' | '\u{2029}' => {
                out.push_str(&format!("\\u{:04x}", c as u32));
            }
            c if (c as u32) < 0x20 => {
                out.push_str(&format!("\\u{:04x}", c as u32));
            }
            c => out.push(c),
        }
    }
    out.push('"');
}

/// Envelope source escaping: the minimal `quote()` form (no HTML escaping,
/// no `\b`/`\f` short forms).
fn escape_envelope(out: &mut String, value: &str) {
    out.push('"');
    for c in value.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => {
                out.push_str(&format!("\\u{:04x}", c as u32));
            }
            c => out.push(c),
        }
    }
    out.push('"');
}

struct Writer {
    out: String,
}

impl Writer {
    fn new() -> Self {
        Self { out: String::new() }
    }

    fn name(&mut self, key: &str) {
        self.comma_if_needed();
        escape_payload(&mut self.out, key);
        self.out.push(':');
    }

    fn value_str(&mut self, value: &str) {
        self.comma_if_needed();
        escape_payload(&mut self.out, value);
    }

    fn value_int(&mut self, value: i64) {
        self.comma_if_needed();
        self.out.push_str(&value.to_string());
    }

    fn value_bool(&mut self, value: bool) {
        self.comma_if_needed();
        self.out.push_str(if value { "true" } else { "false" });
    }

    fn value_null(&mut self) {
        self.comma_if_needed();
        self.out.push_str("null");
    }

    fn begin_object(&mut self) {
        self.comma_if_needed();
        self.out.push('{');
    }

    fn end_object(&mut self) {
        self.out.push('}');
    }

    fn begin_array(&mut self) {
        self.comma_if_needed();
        self.out.push('[');
    }

    fn end_array(&mut self) {
        self.out.push(']');
    }

    fn comma_if_needed(&mut self) {
        if let Some(last) = self.out.chars().last()
            && !matches!(last, '{' | '[' | ':' | ',')
        {
            self.out.push(',');
        }
    }
}

fn write_cell(writer: &mut Writer, cell: &CellRect) {
    writer.begin_object();
    writer.name("cellX");
    writer.value_int(cell.cell_x as i64);
    writer.name("cellY");
    writer.value_int(cell.cell_y as i64);
    writer.name("spanX");
    writer.value_int(cell.span_x as i64);
    writer.name("spanY");
    writer.value_int(cell.span_y as i64);
    writer.end_object();
}

fn write_container(writer: &mut Writer, container: &ContainerRef) {
    writer.begin_object();
    match container {
        ContainerRef::Workspace { page_id } => {
            writer.name("kind");
            writer.value_str("workspace");
            writer.name("pageId");
            // Container page ids always come from validated pages; mirror the
            // writer bound rather than silently clamping.
            writer.value_int(*page_id as i64);
        }
        ContainerRef::Hotseat => {
            writer.name("kind");
            writer.value_str("hotseat");
        }
    }
    writer.end_object();
}

fn write_item(writer: &mut Writer, item: &WorkspaceItem) -> Result<(), CodecError> {
    writer.begin_object();
    writer.name("itemId");
    writer.value_int(encode_u64(item.item_id)?);
    writer.name("payload");
    write_payload_kind(writer, &item.payload)?;
    writer.name("container");
    write_container(writer, &item.container);
    writer.name("cell");
    write_cell(writer, &item.cell);
    writer.end_object();
    Ok(())
}

fn write_folder(writer: &mut Writer, folder: &Folder) -> Result<(), CodecError> {
    writer.begin_object();
    writer.name("folderId");
    writer.value_int(encode_u64(folder.folder_id)?);
    writer.name("title");
    writer.value_str(&folder.title);
    writer.name("members");
    writer.begin_array();
    let mut members = folder.members.clone();
    members.sort_by(|a, b| a.rank.cmp(&b.rank).then_with(|| a.item_id.cmp(&b.item_id)));
    for member in &members {
        writer.begin_object();
        writer.name("itemId");
        writer.value_int(encode_u64(member.item_id)?);
        writer.name("payload");
        write_payload_kind(writer, &member.payload)?;
        writer.name("rank");
        writer.value_int(encode_u64(member.rank as u64)?);
        writer.end_object();
    }
    writer.end_array();
    writer.end_object();
    Ok(())
}

fn write_widget(writer: &mut Writer, widget: &BackupWidgetMetadata) -> Result<(), CodecError> {
    writer.begin_object();
    writer.name("itemId");
    writer.value_int(encode_u64(widget.item_id)?);
    writer.name("minSpanX");
    writer.value_int(widget.min_span_x as i64);
    writer.name("minSpanY");
    writer.value_int(widget.min_span_y as i64);
    writer.name("resizeX");
    writer.value_int(widget.resize_x as i64);
    writer.name("resizeY");
    writer.value_int(widget.resize_y as i64);
    writer.end_object();
    Ok(())
}

fn write_taxonomy(writer: &mut Writer, taxonomy: &DrawerTaxonomy) {
    writer.begin_object();
    writer.name("selected");
    writer.value_str(&taxonomy.selected);
    writer.name("categories");
    writer.begin_array();
    let mut categories = taxonomy.categories.clone();
    categories.sort_by(|a, b| a.id.cmp(&b.id));
    for category in &categories {
        writer.begin_object();
        writer.name("id");
        writer.value_str(&category.id);
        writer.name("title");
        writer.value_str(&category.title);
        writer.end_object();
    }
    writer.end_array();
    writer.name("order");
    writer.begin_array();
    for entry in &taxonomy.order {
        writer.value_str(entry);
    }
    writer.end_array();
    writer.end_object();
}

fn write_drawer(writer: &mut Writer, drawer: &DrawerBackup) -> Result<(), CodecError> {
    writer.begin_object();
    writer.name("mode");
    writer.value_str(&drawer.mode);
    writer.name("personal");
    write_taxonomy(writer, &drawer.personal);
    writer.name("work");
    match &drawer.work {
        None => writer.value_null(),
        Some(taxonomy) => write_taxonomy(writer, taxonomy),
    }
    writer.name("assignments");
    writer.begin_array();
    let mut assignments = drawer.assignments.clone();
    assignments.sort_by(|a, b| {
        a.package
            .cmp(&b.package)
            .then_with(|| a.profile_id.cmp(&b.profile_id))
    });
    for assignment in &assignments {
        writer.begin_object();
        writer.name("package");
        writer.value_str(&assignment.package);
        writer.name("profileId");
        writer.value_int(encode_u64(assignment.profile_id)?);
        writer.name("category");
        writer.value_str(&assignment.category_id);
        writer.end_object();
    }
    writer.end_array();
    writer.name("pack");
    writer.value_str(&drawer.icon_pack);
    writer.name("overrides");
    writer.begin_array();
    let mut overrides = drawer.icon_overrides.clone();
    overrides.sort_by(|a, b| {
        a.package
            .cmp(&b.package)
            .then_with(|| a.class.cmp(&b.class))
            .then_with(|| a.profile_id.cmp(&b.profile_id))
    });
    for icon in &overrides {
        writer.begin_object();
        writer.name("package");
        writer.value_str(&icon.package);
        writer.name("class");
        writer.value_str(&icon.class);
        writer.name("profileId");
        writer.value_int(encode_u64(icon.profile_id)?);
        writer.name("pack");
        writer.value_str(&icon.pack_package);
        writer.name("drawable");
        writer.value_str(&icon.drawable);
        writer.end_object();
    }
    writer.end_array();
    writer.end_object();
    Ok(())
}

fn container_sort_key(
    item: &WorkspaceItem,
    ranks: &std::collections::HashMap<u64, i32>,
) -> (u8, i32) {
    match &item.container {
        ContainerRef::Hotseat => (0, 0),
        ContainerRef::Workspace { page_id } => (1, ranks.get(page_id).copied().unwrap_or(i32::MAX)),
    }
}

fn write_payload(writer: &mut Writer, document: &BackupDocument) -> Result<(), CodecError> {
    let mut pages = document.workspace.pages.clone();
    pages.sort_by(|a, b| a.rank.cmp(&b.rank).then_with(|| a.page_id.cmp(&b.page_id)));
    let ranks: std::collections::HashMap<u64, i32> =
        pages.iter().map(|page| (page.page_id, page.rank)).collect();
    let mut items = document.workspace.items.clone();
    items.sort_by(|a, b| {
        container_sort_key(a, &ranks)
            .cmp(&container_sort_key(b, &ranks))
            .then_with(|| a.cell.cell_y.cmp(&b.cell.cell_y))
            .then_with(|| a.cell.cell_x.cmp(&b.cell.cell_x))
            .then_with(|| a.item_id.cmp(&b.item_id))
    });
    let mut folders = document.workspace.folders.clone();
    folders.sort_by(|a, b| a.folder_id.cmp(&b.folder_id));
    let mut profiles = document.profiles.clone();
    profiles.sort_by_key(|profile| profile.profile_id);
    let mut widgets = document.widgets.clone();
    widgets.sort_by_key(|widget| widget.item_id);

    writer.begin_object();
    writer.name("settings");
    writer.begin_object();
    writer.name("gridName");
    writer.value_str(&document.settings.grid_name);
    writer.name("notificationDots");
    writer.value_bool(document.settings.notification_dots);
    writer.name("themedIcons");
    writer.value_bool(document.settings.themed_icons);
    writer.name("autoAddApps");
    writer.value_bool(document.settings.auto_add_apps);
    writer.end_object();
    writer.name("profiles");
    writer.begin_array();
    for profile in &profiles {
        write_profile(writer, profile)?;
    }
    writer.end_array();
    writer.name("workspace");
    writer.begin_object();
    writer.name("grid");
    writer.begin_object();
    writer.name("cols");
    writer.value_int(document.workspace.grid.cols as i64);
    writer.name("rows");
    writer.value_int(document.workspace.grid.rows as i64);
    writer.name("hotseatCols");
    writer.value_int(document.workspace.grid.hotseat_cols as i64);
    writer.end_object();
    writer.name("pages");
    writer.begin_array();
    for page in &pages {
        writer.begin_object();
        writer.name("pageId");
        writer.value_int(encode_u64(page.page_id)?);
        writer.name("rank");
        writer.value_int(page.rank as i64);
        writer.end_object();
    }
    writer.end_array();
    writer.name("items");
    writer.begin_array();
    for item in &items {
        write_item(writer, item)?;
    }
    writer.end_array();
    writer.name("folders");
    writer.begin_array();
    for folder in &folders {
        write_folder(writer, folder)?;
    }
    writer.end_array();
    writer.end_object();
    writer.name("widgets");
    writer.begin_array();
    for widget in &widgets {
        write_widget(writer, widget)?;
    }
    writer.end_array();
    writer.name("drawer");
    write_drawer(writer, &document.drawer)?;
    writer.name("nextItemId");
    writer.value_int(encode_u64(document.next_item_id)?);
    writer.name("nextPageId");
    writer.value_int(encode_u64(document.next_page_id)?);
    writer.end_object();
    Ok(())
}

/// Canonical payload bytes for one document.
fn encode_payload(document: &BackupDocument) -> Result<Vec<u8>, CodecError> {
    let mut writer = Writer::new();
    write_payload(&mut writer, document)?;
    Ok(writer.out.into_bytes())
}

/// Full envelope bytes: `{"format","version","source","payload","payloadSha256"}`.
/// Requires a v2 document, mirroring `write()`.
pub fn encode_backup_envelope(document: &BackupDocument) -> Result<Vec<u8>, CodecError> {
    if document.format_version != BACKUP_FORMAT_VERSION {
        return Err(CodecError::Version);
    }
    let payload = encode_payload(document)?;
    let sha = sha256_hex(&payload);
    let mut out = String::new();
    out.push_str("{\"format\":\"");
    out.push_str(CODEC_FORMAT);
    out.push_str("\",\"version\":");
    out.push_str(&CODEC_VERSION.to_string());
    out.push_str(",\"source\":{\"version\":");
    escape_envelope(&mut out, &document.source_version);
    out.push_str(",\"commit\":");
    escape_envelope(&mut out, &document.source_commit);
    out.push_str("},\"payload\":");
    out.push_str(std::str::from_utf8(&payload).map_err(|_| CodecError::Utf8)?);
    out.push_str(",\"payloadSha256\":\"");
    out.push_str(&sha);
    out.push_str("\"}");
    let bytes = out.into_bytes();
    // The writer must never produce an artifact its own reader rejects.
    if bytes.len() > MAX_BYTES {
        return Err(CodecError::Oversized);
    }
    Ok(bytes)
}

/// Restore-journal snapshot: versioned object with real target serials. Never
/// exported as a backup; replayed after process death instead.
pub fn encode_drawer_journal(
    drawer: &DrawerBackup,
    targets: &[BackupProfileRef],
) -> Result<String, CodecError> {
    let mut writer = Writer::new();
    writer.begin_object();
    writer.name("journalVersion");
    writer.value_int(JOURNAL_VERSION);
    writer.name("profiles");
    writer.begin_array();
    let mut sorted = targets.to_vec();
    sorted.sort_by_key(|profile| profile.profile_id);
    for profile in &sorted {
        write_profile(&mut writer, profile)?;
    }
    writer.end_array();
    writer.name("drawer");
    write_drawer(&mut writer, drawer)?;
    writer.end_object();
    if writer.out.len() > MAX_BYTES {
        return Err(CodecError::Oversized);
    }
    Ok(writer.out)
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DrawerJournal {
    pub drawer: DrawerBackup,
    pub targets: Vec<BackupProfileRef>,
}

/// Decode a restore journal. Shape is validated before the version is
/// classified: an empty or truncated object is corruption, while a
/// structurally valid journal without a version is obsolete development state.
pub fn decode_drawer_journal(payload: &str) -> Result<DrawerJournal, CodecError> {
    if payload.len() > MAX_BYTES {
        return Err(CodecError::Oversized);
    }
    let value = parse_document(payload)?;
    let members = as_object(&value).map_err(|_| CodecError::Malformed)?;
    let mut journal_version: Option<i64> = None;
    let mut profiles: Option<Vec<BackupProfileRef>> = None;
    let mut drawer: Option<DrawerBackup> = None;
    for (key, val) in members {
        match key.as_str() {
            "journalVersion" => {
                journal_version = Some(exact_long(val).map_err(|_| CodecError::Journal)?)
            }
            "profiles" => {
                profiles = Some(read_list(
                    val,
                    MAX_PROFILES,
                    CodecError::Profiles,
                    read_profile,
                )?)
            }
            "drawer" => drawer = Some(read_drawer(val, false)?.ok_or(CodecError::Drawer)?),
            other => skip_forbidden(other)?,
        }
    }
    let drawer = drawer.ok_or(CodecError::Drawer)?;
    let profiles = profiles.ok_or(CodecError::Profile)?;
    match journal_version {
        None => Err(CodecError::Obsolete),
        Some(JOURNAL_VERSION) => Ok(DrawerJournal {
            drawer,
            targets: profiles,
        }),
        Some(_) => Err(CodecError::Journal),
    }
}

fn write_profile(writer: &mut Writer, profile: &BackupProfileRef) -> Result<(), CodecError> {
    writer.begin_object();
    writer.name("profileId");
    writer.value_int(encode_u64(profile.profile_id)?);
    writer.name("kind");
    match profile.kind {
        ProfileKind::Personal => writer.value_str("personal"),
        ProfileKind::Work => writer.value_str("work"),
        ProfileKind::Private | ProfileKind::Other => return Err(CodecError::Profile),
    }
    writer.end_object();
    Ok(())
}

fn write_payload_kind(writer: &mut Writer, payload: &ItemPayload) -> Result<(), CodecError> {
    writer.begin_object();
    match payload {
        ItemPayload::Application(component) => {
            writer.name("kind");
            writer.value_str("application");
            writer.name("package");
            writer.value_str(&component.package);
            writer.name("class");
            writer.value_str(&component.class);
            writer.name("profileId");
            writer.value_int(encode_u64(component.profile_id)?);
        }
        ItemPayload::Folder => {
            writer.name("kind");
            writer.value_str("folder");
        }
        ItemPayload::Shortcut(shortcut) => {
            writer.name("kind");
            writer.value_str("shortcut");
            writer.name("package");
            writer.value_str(&shortcut.package);
            writer.name("shortcutId");
            writer.value_str(&shortcut.shortcut_id);
            writer.name("profileId");
            writer.value_int(encode_u64(shortcut.profile_id)?);
        }
        ItemPayload::Widget(provider) => {
            writer.name("kind");
            writer.value_str("widget");
            writer.name("package");
            writer.value_str(&provider.package);
            writer.name("class");
            writer.value_str(&provider.class);
            writer.name("profileId");
            writer.value_int(encode_u64(provider.profile_id)?);
        }
    }
    writer.end_object();
    Ok(())
}

fn read_payload_doc(
    value: &SVal,
    source_version: &str,
    source_commit: &str,
    version: i64,
) -> Result<BackupDocument, CodecError> {
    let members = as_object(value).map_err(|_| CodecError::Malformed)?;
    let mut settings: Option<BackupSettings> = None;
    let mut profiles: Option<Vec<BackupProfileRef>> = None;
    let mut workspace: Option<WorkspaceSnapshot> = None;
    let mut widgets: Option<Vec<BackupWidgetMetadata>> = None;
    let mut drawer: Option<DrawerBackup> = None;
    let mut next_item_id: Option<u64> = None;
    let mut next_page_id: Option<u64> = None;
    for (key, val) in members {
        match key.as_str() {
            "settings" => settings = Some(read_settings(val)?),
            "profiles" => {
                profiles = Some(read_list(
                    val,
                    MAX_PROFILES,
                    CodecError::Profiles,
                    read_profile,
                )?)
            }
            "workspace" => workspace = Some(read_workspace(val)?),
            "widgets" => {
                widgets = Some(read_list(
                    val,
                    MAX_WIDGETS,
                    CodecError::Widgets,
                    read_widget,
                )?)
            }
            "drawer" => drawer = read_drawer(val, version == CODEC_VERSION_V1 as i64)?,
            "nextItemId" => next_item_id = Some(exact_u64(val)?),
            "nextPageId" => next_page_id = Some(exact_u64(val)?),
            other => skip_forbidden(other)?,
        }
    }
    Ok(BackupDocument {
        format_version: BACKUP_FORMAT_VERSION,
        source_version: source_version.to_string(),
        source_commit: source_commit.to_string(),
        settings: settings.ok_or(CodecError::Settings)?,
        profiles: profiles.ok_or(CodecError::Profiles)?,
        workspace: workspace.ok_or(CodecError::Workspace)?,
        widgets: widgets.ok_or(CodecError::Widgets)?,
        // v1 envelopes predate drawer customization and upgrade with empty
        // defaults; a v2 envelope without its required drawer section is corrupt.
        drawer: drawer
            .or(if version == CODEC_VERSION_V1 as i64 {
                Some(DrawerBackup::default())
            } else {
                None
            })
            .ok_or(CodecError::Drawer)?,
        next_item_id: next_item_id.ok_or(CodecError::Allocator)?,
        next_page_id: next_page_id.ok_or(CodecError::Allocator)?,
    })
}
#[cfg(test)]
mod tests {
    use super::*;
    use crate::backup::BackupImportTarget;

    const VALID_V2: &[u8] =
        include_bytes!("../../../android/app/src/test/resources/backup/valid-v2.json");
    const VALID_V1: &[u8] =
        include_bytes!("../../../android/app/src/test/resources/backup/valid-v1.json");

    fn decode(bytes: &[u8]) -> BackupDocument {
        decode_backup_envelope(bytes).expect("fixture must decode")
    }

    #[test]
    fn golden_v2_roundtrip_is_byte_identical() {
        let document = decode(VALID_V2);
        assert_eq!(document.format_version, BACKUP_FORMAT_VERSION);
        assert_eq!(encode_backup_envelope(&document).unwrap(), VALID_V2);
    }

    #[test]
    fn v1_import_upgrades_with_empty_drawer() {
        let document = decode(VALID_V1);
        assert_eq!(document.format_version, BACKUP_FORMAT_VERSION);
        assert_eq!(document.drawer, DrawerBackup::default());
        // Re-export upgrades the envelope to v2.
        let upgraded = encode_backup_envelope(&document).unwrap();
        let reparsed = decode_backup_envelope(&upgraded).unwrap();
        assert_eq!(reparsed.format_version, BACKUP_FORMAT_VERSION);
        assert_eq!(reparsed.drawer, DrawerBackup::default());
    }

    #[test]
    fn decoded_v2_matches_expected_content() {
        let document = decode(VALID_V2);
        assert_eq!(document.settings.grid_name, "4_by_5");
        assert_eq!(document.drawer.mode, "sections");
        assert_eq!(document.drawer.personal.selected, "games");
        assert_eq!(document.drawer.icon_pack, "com.example.pack");
        assert_eq!(document.drawer.assignments.len(), 1);
        assert_eq!(document.drawer.icon_overrides.len(), 1);
        assert!(document.drawer.work.is_some());
    }

    #[test]
    fn writer_rejects_non_v2_documents() {
        let mut document = decode(VALID_V2);
        document.format_version = 1;
        assert_eq!(encode_backup_envelope(&document), Err(CodecError::Version));
    }

    #[test]
    fn v2_envelope_without_drawer_is_corrupt() {
        // Same bytes as the v1 fixture except the envelope version: the missing
        // drawer section must fail, not import with empty customization.
        let text = std::str::from_utf8(VALID_V1).unwrap();
        let upgraded = text.replacen("\"version\":1", "\"version\":2", 1);
        assert_eq!(
            decode_backup_envelope(upgraded.as_bytes()),
            Err(CodecError::Drawer)
        );
    }

    #[test]
    fn writer_rejects_artifacts_over_the_reader_limit() {
        let big = "a".repeat(256);
        let mut document = decode(VALID_V2);
        document.workspace.items = (1..=MAX_ITEMS as u64)
            .map(|id| crate::workspace::WorkspaceItem {
                item_id: id,
                payload: crate::workspace::ItemPayload::Application(
                    crate::workspace::ComponentId {
                        package: big.clone(),
                        class: big.clone(),
                        profile_id: 0,
                    },
                ),
                container: crate::workspace::ContainerRef::Workspace { page_id: 1 },
                cell: crate::workspace::CellRect {
                    cell_x: 0,
                    cell_y: 0,
                    span_x: 1,
                    span_y: 1,
                },
            })
            .collect();
        assert_eq!(
            encode_backup_envelope(&document),
            Err(CodecError::Oversized)
        );
    }

    #[test]
    fn payload_strings_match_android_json_writer() {
        // Android's JsonWriter passes printable characters (including `<>&='`)
        // through literally and escapes only U+2028/U+2029 plus other controls.
        let mut document = decode(VALID_V2);
        document.workspace.folders[0].title = "<a>&'=\u{2028}\u{2029}".to_string();
        let bytes = encode_backup_envelope(&document).unwrap();
        let text = std::str::from_utf8(&bytes).unwrap();
        assert!(text.contains("\"title\":\"<a>&'=\\u2028\\u2029\""));
        // Round-trips back to the original title.
        assert_eq!(decode_backup_envelope(&bytes).unwrap(), document);
    }

    #[test]
    fn envelope_source_uses_minimal_escaping() {
        let mut document = decode(VALID_V2);
        document.source_version = "1<2".to_string();
        let bytes = encode_backup_envelope(&document).unwrap();
        let text = std::str::from_utf8(&bytes).unwrap();
        assert!(text.contains("\"version\":\"1<2\""));
    }

    #[test]
    fn error_reasons_match_kotlin_classifier() {
        let cases: &[(&[u8], CodecError)] = &[
            (b"{}", CodecError::Payload),
            // The payload scan runs before envelope checks, mirroring Kotlin.
            (b"{\"format\":\"x\",\"version\":2}", CodecError::Payload),
            (
                b"{\"format\":\"x\",\"version\":2,\"source\":{\"version\":\"1\",\"commit\":\"a\"},\"payload\":{},\"payloadSha256\":\"0000000000000000000000000000000000000000000000000000000000000000\"}",
                CodecError::Format,
            ),
            (
                b"{\"format\":\"com.caniko.cenix.backup\",\"version\":3,\"source\":{\"version\":\"1\",\"commit\":\"a\"},\"payload\":{},\"payloadSha256\":\"x\"}",
                CodecError::Version,
            ),
            (b"\xff\xfe", CodecError::Utf8),
            (b"", CodecError::Truncated),
            (b"{\"payload\":{},\"a\":1,\"a\":2}", CodecError::DuplicateKey),
            (b"{\"payload\":{},\"generation\":1}", CodecError::Forbidden),
            (b"{\"payload\":{},\"x\":[1,]}", CodecError::Malformed),
            (b"{\"payload\":{},\"x\":", CodecError::Truncated),
            (b"{\"payload\":{", CodecError::Truncated),
        ];
        for (input, expected) in cases {
            assert_eq!(
                decode_backup_envelope(input),
                Err(*expected),
                "input: {}",
                String::from_utf8_lossy(input)
            );
        }
    }

    #[test]
    fn checksum_mismatch_is_rejected() {
        let mut text = std::str::from_utf8(VALID_V2).unwrap().to_string();
        let tag = "\"payloadSha256\":\"";
        let start = text.find(tag).unwrap() + tag.len();
        text.replace_range(
            start..start + 1,
            if &text[start..start + 1] == "0" {
                "1"
            } else {
                "0"
            },
        );
        assert_eq!(
            decode_backup_envelope(text.as_bytes()),
            Err(CodecError::Checksum)
        );
    }

    #[test]
    fn journal_roundtrip_carries_version_and_targets() {
        let document = decode(VALID_V2);
        let targets = vec![
            BackupProfileRef {
                profile_id: 42,
                kind: ProfileKind::Personal,
            },
            BackupProfileRef {
                profile_id: 99,
                kind: ProfileKind::Work,
            },
        ];
        let journal = encode_drawer_journal(&document.drawer, &targets).unwrap();
        let decoded = decode_drawer_journal(&journal).unwrap();
        assert_eq!(decoded.drawer, document.drawer);
        assert_eq!(decoded.targets, targets);
    }

    #[test]
    fn journal_without_version_is_obsolete_not_corrupt() {
        let journal = encode_drawer_journal(
            &decode(VALID_V2).drawer,
            &[BackupProfileRef {
                profile_id: 42,
                kind: ProfileKind::Personal,
            }],
        )
        .unwrap();
        let legacy = journal.replace("\"journalVersion\":1,", "");
        assert_eq!(decode_drawer_journal(&legacy), Err(CodecError::Obsolete));
        assert_eq!(decode_drawer_journal("{}"), Err(CodecError::Drawer));
    }

    #[test]
    fn decoded_shape_rejects_bad_profiles_and_overlap() {
        let mut document = decode(VALID_V2);
        document.profiles.clear();
        assert_eq!(validate_decoded_shape(&document), Err(CodecError::Profile));
        let mut document = decode(VALID_V2);
        document.drawer.assignments[0].profile_id = 9;
        assert_eq!(validate_decoded_shape(&document), Err(CodecError::Profile));
    }

    #[test]
    fn decoded_document_passes_semantic_validation() {
        // The golden fixture must satisfy the existing semantic gate unchanged.
        let document = decode(VALID_V2);
        crate::backup::validate_backup_document(document).unwrap();
        let v1 = decode(VALID_V1);
        crate::backup::validate_backup_document(v1).unwrap();
    }

    macro_rules! fixture {
        ($name:literal) => {
            include_bytes!(concat!(
                "../../../android/app/src/test/resources/backup/",
                $name
            ))
        };
    }

    #[test]
    fn rejection_fixtures_fail_closed() {
        // (file, expected reason); ported from BackupJsonCodecTest.
        let cases: &[(&[u8], CodecError)] = &[
            // Cut mid-envelope before any payload key: the slice scan fails first,
            // exactly like Kotlin's payloadSlice.
            (fixture!("truncated.json"), CodecError::Payload),
            (fixture!("checksum-corrupt.json"), CodecError::Checksum),
            (fixture!("overlap.json"), CodecError::Overlap),
            (fixture!("duplicate-id.json"), CodecError::Duplicate),
            (fixture!("mixed-profile-folder.json"), CodecError::Mixed),
            (fixture!("private-profile.json"), CodecError::Profile),
            (fixture!("future-version.json"), CodecError::Version),
        ];
        for (input, expected) in cases {
            assert_eq!(&decode_backup_envelope(input), &Err(*expected));
        }
    }

    #[test]
    fn unknown_fields_and_nested_payload_are_ignored() {
        // The unknown-field fixture is a v1 envelope: it decodes like v1 imports.
        let v1 = decode_backup_envelope(VALID_V1).unwrap();
        let unknown = include_bytes!(
            "../../../android/app/src/test/resources/backup/unknown-current-field.json"
        );
        assert_eq!(decode_backup_envelope(unknown).unwrap(), v1);
        let v2 = decode_backup_envelope(VALID_V2).unwrap();
        let text = std::str::from_utf8(VALID_V2).unwrap();
        let nested = text.replacen(
            "\"commit\":\"abc123\"",
            "\"commit\":\"abc123\",\"payload\":{}",
            1,
        );
        assert_eq!(decode_backup_envelope(nested.as_bytes()).unwrap(), v2);
    }

    #[test]
    fn oversized_malformed_utf8_and_overflow_inputs_fail() {
        assert_eq!(
            decode_backup_envelope(&vec![b'{'; MAX_BYTES + 1]),
            Err(CodecError::Oversized)
        );
        assert_eq!(
            decode_backup_envelope(&[0x7b, 0xff, 0x7d]),
            Err(CodecError::Utf8)
        );
        let overflow = "{\"settings\":{\"gridName\":\"4_by_5\",\"notificationDots\":true,\"themedIcons\":false,\"autoAddApps\":true},\"profiles\":[{\"profileId\":0,\"kind\":\"personal\"}],\"workspace\":{\"grid\":{\"cols\":4,\"rows\":5,\"hotseatCols\":4},\"pages\":[{\"pageId\":1,\"rank\":0}],\"items\":[],\"folders\":[]},\"widgets\":[],\"drawer\":{\"mode\":\"sections\",\"personal\":{\"selected\":\"all\",\"categories\":[],\"order\":[]},\"work\":null,\"assignments\":[],\"pack\":\"\",\"overrides\":[]},\"nextItemId\":9223372036854775807,\"nextPageId\":2}";
        let envelope = format!(
            "{{\"format\":\"{CODEC_FORMAT}\",\"version\":{CODEC_VERSION},\"source\":{{\"version\":\"1.0.0\",\"commit\":\"abc123\"}},\"payload\":{overflow},\"payloadSha256\":\"{}\"}}",
            sha256_hex(overflow.as_bytes())
        );
        // nextItemId == Long.MAX_VALUE is rejected exactly like Kotlin's exactU64.
        assert_eq!(
            decode_backup_envelope(envelope.as_bytes()),
            Err(CodecError::Integer)
        );
    }

    #[test]
    fn decoded_document_plans_import() {
        let document = decode(VALID_V2);
        let target = BackupImportTarget {
            generation: 0,
            expected_generation: 0,
            supported_grid_names: vec!["4_by_5".to_string()],
            profiles: vec![BackupProfileRef {
                profile_id: 50,
                kind: ProfileKind::Personal,
            }],
            applications: Vec::new(),
            shortcuts: Vec::new(),
            widget_providers: Vec::new(),
        };
        let plan = crate::backup::plan_backup_import(
            document,
            target,
            vec![crate::backup::ProfileMapping {
                source_profile_id: 0,
                target_profile_id: 50,
            }],
        )
        .unwrap();
        assert_eq!(plan.drawer.assignments.len(), 1);
    }
}
