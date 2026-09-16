//! Single owner for drawer category rules shared by live editing, drawer
//! projection, export, and restore.
//!
//! Android keeps preferences IO, localized strings, locale collation, and the
//! platform `ApplicationInfo` mapping. Every rule derived from stored values —
//! identifier syntax, title hygiene, ordering, assignment, section keys — lives
//! here so it is deterministic and unit-tested in Rust. Kotlin only reads and
//! writes preferences and forwards values across UniFFI.

/// Canonical built-in categories in display order, ending with the fallback.
pub const BUILTIN_CATEGORIES: [&str; 10] = [
    "games",
    "productivity",
    "social",
    "audio",
    "video",
    "image",
    "news",
    "maps",
    "accessibility",
    "uncategorized",
];

pub const ALL_CATEGORY: &str = "all";
pub const UNCATEGORIZED: &str = "uncategorized";
pub const MAX_CUSTOM_CATEGORIES: usize = 64;
pub const MAX_ORDER_ENTRIES: usize = 128;
pub const MAX_TITLE_CHARS: usize = 40;
pub const MAX_ID_CHARS: usize = 64;

/// Identifier syntax shared by custom, built-in, and assignment references.
pub fn is_category_id(value: &str) -> bool {
    !value.is_empty()
        && value.chars().count() <= MAX_ID_CHARS
        && value
            .chars()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_')
}

/// Whether `value` may be introduced as a new custom category: well-formed and
/// not colliding with the `all` selector or a built-in.
pub fn is_valid_custom_id(value: &str) -> bool {
    is_category_id(value) && value != ALL_CATEGORY && !BUILTIN_CATEGORIES.contains(&value)
}

/// Clean a user-supplied title: trim, bound, and reject empty or control-laden
/// input. Returns `None` exactly when Kotlin must refuse the edit.
pub fn sanitize_title(raw: &str) -> Option<String> {
    let clean: String = raw.trim().chars().take(MAX_TITLE_CHARS).collect();
    if clean.is_empty() || clean.chars().any(char::is_control) {
        return None;
    }
    Some(clean)
}

/// Order built-ins (canonical order) followed by custom ids (sorted), then
/// apply the stored order as a stable preference: listed ids first in listed
/// order (deduplicated), everything else after in default order. Unknown order
/// entries are ignored; unknown categories are never invented.
pub fn order_categories(custom_ids: &[String], order: &[String]) -> Vec<String> {
    let mut customs: Vec<&str> = custom_ids
        .iter()
        .map(String::as_str)
        .filter(|id| is_valid_custom_id(id))
        .collect();
    customs.sort_unstable();
    customs.dedup();
    let mut default: Vec<&str> = BUILTIN_CATEGORIES.to_vec();
    default.extend(customs);
    let mut rank: std::collections::HashMap<&str, usize> = std::collections::HashMap::new();
    for id in order {
        if default.contains(&id.as_str()) && !rank.contains_key(id.as_str()) {
            rank.insert(id.as_str(), rank.len());
        }
    }
    let mut ordered = default;
    ordered.sort_by_key(|id| rank.get(id).copied().unwrap_or(usize::MAX));
    ordered.into_iter().map(str::to_string).collect()
}

/// Assignment for one app: package-level overrides win for customizable apps;
/// anything else keeps its automatic category.
pub fn assignment_category(
    auto: &str,
    category_override: Option<&str>,
    supports_customization: bool,
) -> String {
    if supports_customization && let Some(custom) = category_override {
        return custom.to_string();
    }
    auto.to_string()
}

/// Section key for drawer projection: the requested category when the drawer
/// knows it, otherwise the uncategorized fallback. Private-space exclusion and
/// locale sorting stay with the Android renderer.
pub fn section_key(requested: &str, known: &[String]) -> String {
    if known.iter().any(|id| id == requested) {
        requested.to_string()
    } else {
        UNCATEGORIZED.to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn custom_id_rules_reject_builtins_all_and_syntax() {
        assert!(is_valid_custom_id("user_ab12cd34"));
        for bad in [
            "",
            "all",
            "games",
            "Productivity",
            "has space",
            "UPPER",
            "with-dash",
            "unicode-é",
        ] {
            assert!(!is_valid_custom_id(bad), "{bad:?} must be rejected");
        }
        assert!(!is_valid_custom_id(&"u".repeat(MAX_ID_CHARS + 1)));
    }

    #[test]
    fn titles_trim_bound_and_reject_controls() {
        assert_eq!(sanitize_title("  Focus  "), Some("Focus".to_string()));
        assert_eq!(sanitize_title(""), None);
        assert_eq!(sanitize_title("   "), None);
        assert_eq!(sanitize_title("a\tb"), None);
        assert_eq!(sanitize_title(&"x".repeat(80)).unwrap().len(), 40);
    }

    #[test]
    fn ordering_applies_stored_preference_without_inventing_ids() {
        let customs = vec![
            "user_b".to_string(),
            "user_a".to_string(),
            "games".to_string(),
        ];
        let ordered = order_categories(&customs, &["user_b".to_string(), "ghost".to_string()]);
        assert_eq!(
            &ordered[..2],
            &["user_b".to_string(), "games".to_string()][..]
        );
        assert!(ordered.contains(&"user_a".to_string()));
        assert!(!ordered.iter().any(|id| id == "ghost"));
        assert_eq!(ordered.len(), BUILTIN_CATEGORIES.len() + 2);
        // No stored order: canonical built-ins first, customs sorted after.
        let ordered = order_categories(&customs, &[]);
        assert_eq!(&ordered[..BUILTIN_CATEGORIES.len()], &BUILTIN_CATEGORIES);
        assert_eq!(
            &ordered[BUILTIN_CATEGORIES.len()..],
            &["user_a".to_string(), "user_b".to_string()][..]
        );
    }

    #[test]
    fn ordering_is_a_stable_fixpoint_over_generated_inputs() {
        let mut seed = 0x2545F4914F6CDD1Du64;
        let mut next = || {
            seed ^= seed << 13;
            seed ^= seed >> 7;
            seed ^= seed << 17;
            seed
        };
        let alphabet = ["user_a", "user_b", "user_c", "games", "Bogus", ""];
        for round in 0..50 {
            let customs: Vec<String> = (0..(next() % 5))
                .map(|_| alphabet[(next() as usize) % alphabet.len()].to_string())
                .collect();
            let order: Vec<String> = (0..(next() % 5))
                .map(|_| alphabet[(next() as usize) % alphabet.len()].to_string())
                .collect();
            let once = order_categories(&customs, &order);
            // Valid customs all survive exactly once; nothing is invented.
            let mut expected: Vec<String> =
                BUILTIN_CATEGORIES.iter().map(|id| id.to_string()).collect();
            let mut valid: Vec<String> = customs
                .iter()
                .filter(|id| is_valid_custom_id(id))
                .cloned()
                .collect();
            valid.sort_unstable();
            valid.dedup();
            expected.extend(valid);
            let mut have = once.clone();
            have.sort_unstable();
            expected.sort_unstable();
            assert_eq!(
                have, expected,
                "ordering preserves the id set (round {round})"
            );
            // Feeding the result back as the stored order is a fixpoint.
            assert_eq!(
                order_categories(&customs, &once),
                once,
                "not stable (round {round})"
            );
        }
    }

    #[test]
    fn assignment_prefers_package_override_and_section_falls_back() {
        assert_eq!(assignment_category("games", Some("user_a"), true), "user_a");
        assert_eq!(assignment_category("games", Some("user_a"), false), "games");
        assert_eq!(assignment_category("games", None, true), "games");
        let known = vec!["games".to_string(), "user_a".to_string()];
        assert_eq!(section_key("user_a", &known), "user_a");
        assert_eq!(section_key("deleted", &known), "uncategorized");
    }
}
