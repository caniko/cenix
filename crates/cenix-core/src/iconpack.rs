//! Bounded parser for launcher icon-pack index XML (`appfilter.xml` style).
//!
//! Only `<item component drawable>` entries are indexed; every other element is
//! skipped. Bounds (document size enforced by the caller, event/depth/item caps
//! here) keep hostile packs from exhausting memory. `<!DOCTYPE>` is rejected so
//! no external entities are ever resolved.

pub const MAX_EVENTS: usize = 200_000;
pub const MAX_ITEMS: usize = 50_000;
pub const MAX_DEPTH: usize = 8;
pub const MAX_ATTRIBUTES: usize = 32;
pub const MAX_COMPONENT_CHARS: usize = 512;
pub const MAX_NAME_CHARS: usize = 256;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IconPackIndex {
    /// `package/class` flattened component → drawable resource name. First wins.
    pub mappings: Vec<(String, String)>,
    /// Every valid drawable name, including mapping-only items.
    pub drawables: Vec<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum IconPackError {
    Malformed(&'static str),
    Bounds(&'static str),
}

/// Drawable resource names: short ASCII identifiers, no path escapes.
pub fn resource_name(value: &str) -> Option<&str> {
    if value.is_empty() || value.len() > MAX_NAME_CHARS {
        return None;
    }
    if value
        .bytes()
        .all(|b| b.is_ascii_alphanumeric() || b == b'_')
    {
        Some(value)
    } else {
        None
    }
}

/// Mirror of `ComponentName.unflattenFromString` + `flattenToString` for the
/// `ComponentInfo{pkg/cls}` spellings icon packs use. Returns `None` exactly
/// when Android would (missing separator or empty package).
fn flatten_component(raw: &str) -> Option<String> {
    let raw = raw
        .strip_prefix("ComponentInfo{")
        .and_then(|s| s.strip_suffix('}'))
        .unwrap_or(raw);
    let (package, class) = raw.split_once('/')?;
    if package.is_empty() {
        return None;
    }
    let class = if let Some(rest) = class.strip_prefix('.') {
        format!("{package}.{rest}")
    } else {
        class.to_string()
    };
    Some(format!("{package}/{class}"))
}

/// Index one icon-pack XML document. Tokenizing and well-formedness come from
/// quick-xml (mismatched tags rejected via end-name checking); the Cenix item
/// policy on top is unchanged. The caller enforces the byte cap before
/// invoking (mirrors `IconPackXml.MAX_BYTES`).
pub fn parse_index(xml: &[u8]) -> Result<IconPackIndex, IconPackError> {
    use quick_xml::events::Event;
    use quick_xml::reader::Reader;

    let text =
        std::str::from_utf8(xml).map_err(|_| IconPackError::Malformed("non-UTF8 document"))?;
    if !text.trim_start().starts_with('<') {
        return Err(IconPackError::Malformed("expected document"));
    }
    let mut reader = Reader::from_reader(xml);
    reader.config_mut().trim_text(true);
    reader.config_mut().expand_empty_elements = true;
    reader.config_mut().check_end_names = true;
    let mut buf = Vec::new();
    let mut mappings: Vec<(String, String)> = Vec::new();
    let mut seen_components: std::collections::HashSet<String> = std::collections::HashSet::new();
    let mut drawables: Vec<String> = Vec::new();
    let mut seen_drawables: std::collections::HashSet<String> = std::collections::HashSet::new();
    let mut items = 0usize;
    let mut events = 0usize;
    let mut depth = 0usize;
    let mut roots = 0usize;
    loop {
        events += 1;
        if events > MAX_EVENTS {
            return Err(IconPackError::Bounds("too many XML events"));
        }
        match reader
            .read_event_into(&mut buf)
            .map_err(|_| IconPackError::Malformed("malformed XML"))?
        {
            Event::Start(element) => {
                depth += 1;
                if depth > MAX_DEPTH {
                    return Err(IconPackError::Bounds("document too deep"));
                }
                if depth == 1 {
                    roots += 1;
                    if roots > 1 {
                        return Err(IconPackError::Malformed("multiple roots"));
                    }
                }
                if element.name().as_ref() == b"item" {
                    items += 1;
                    if items > MAX_ITEMS {
                        return Err(IconPackError::Bounds("too many items"));
                    }
                    index_item(
                        &reader,
                        &element,
                        &mut mappings,
                        &mut seen_components,
                        &mut drawables,
                        &mut seen_drawables,
                    )?;
                } else {
                    // Bounds apply to every element, not just indexed items.
                    bound_attributes(&element)?;
                }
            }
            Event::End(_) => {
                depth = depth.saturating_sub(1);
            }
            Event::Empty(_) => {
                // `expand_empty_elements` turns these into Start/End pairs, so
                // reaching one means the config was bypassed; still count it.
                items += 1;
                if items > MAX_ITEMS {
                    return Err(IconPackError::Bounds("too many items"));
                }
            }
            Event::Text(text) => {
                // Element content is meaningless to the index; non-whitespace
                // text outside the single root is not a document we accept.
                if depth == 0
                    && text
                        .decode()
                        .map(|content| content.chars().any(|c| !c.is_whitespace()))
                        .unwrap_or(true)
                {
                    return Err(IconPackError::Malformed("text outside root"));
                }
            }
            Event::Comment(_) | Event::CData(_) | Event::Decl(_) | Event::PI(_) => {}
            // DOCTYPE and any other declaration: rejected, never resolved.
            Event::DocType(_) => return Err(IconPackError::Malformed("declaration not allowed")),
            Event::Eof => break,
            _ => {}
        }
        buf.clear();
    }
    if depth != 0 {
        return Err(IconPackError::Malformed("unbalanced document"));
    }
    if roots != 1 {
        return Err(IconPackError::Malformed("expected document"));
    }
    Ok(IconPackIndex {
        mappings,
        drawables,
    })
}

/// Attribute-count bound for elements the index otherwise skips.
fn bound_attributes(element: &quick_xml::events::BytesStart) -> Result<(), IconPackError> {
    let mut count = 0usize;
    for attr in element.attributes() {
        attr.map_err(|_| IconPackError::Malformed("bad attribute"))?;
        count += 1;
        if count > MAX_ATTRIBUTES {
            return Err(IconPackError::Bounds("too many attributes"));
        }
    }
    Ok(())
}

fn index_item(
    reader: &quick_xml::reader::Reader<&[u8]>,
    element: &quick_xml::events::BytesStart,
    mappings: &mut Vec<(String, String)>,
    seen_components: &mut std::collections::HashSet<String>,
    drawables: &mut Vec<String>,
    seen_drawables: &mut std::collections::HashSet<String>,
) -> Result<(), IconPackError> {
    let mut attrs = Vec::new();
    for attr in element.attributes() {
        let attr = attr.map_err(|_| IconPackError::Malformed("bad attribute"))?;
        if attrs.len() >= MAX_ATTRIBUTES {
            return Err(IconPackError::Bounds("too many attributes"));
        }
        let key = std::str::from_utf8(attr.key.as_ref())
            .map_err(|_| IconPackError::Malformed("non-UTF8 attribute"))?
            .to_string();
        let value = attr
            .decoded_and_normalized_value(quick_xml::XmlVersion::Implicit1_0, reader.decoder())
            .map_err(|_| IconPackError::Malformed("bad entity"))?
            .into_owned();
        attrs.push((key, value));
    }
    let drawable = attrs
        .iter()
        .find(|(k, _)| k == "drawable")
        .map(|(_, v)| v.as_str());
    let Some(name) = drawable.and_then(resource_name) else {
        return Ok(());
    };
    if seen_drawables.insert(name.to_string()) {
        drawables.push(name.to_string());
    }
    let component = attrs
        .iter()
        .find(|(k, _)| k == "component")
        .map(|(_, v)| v.as_str());
    if let Some(raw) = component
        && raw.len() <= MAX_COMPONENT_CHARS
        && let Some(flat) = flatten_component(raw)
        && seen_components.insert(flat.clone())
    {
        mappings.push((flat, name.to_string()));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn parse(text: &str) -> Result<IconPackIndex, IconPackError> {
        parse_index(text.as_bytes())
    }

    #[test]
    fn mappings_expand_activities_without_inventing_package_fallbacks() {
        let result = parse(
            "<resources>
            <item component=\"ComponentInfo{app.pkg/.Main}\" drawable=\"main\"/>
            <item component=\"ComponentInfo{app.pkg/.Other}\" drawable=\"other\"/>
            <item component=\"ComponentInfo{app.pkg/.Main}\" drawable=\"duplicate\"/>
            <item component=\"ComponentInfo{app.pkg/.Bad}\" drawable=\"../outside\"/>
            <calendar component=\"ComponentInfo{app.pkg/.Calendar}\" prefix=\"day_\"/>
        </resources>",
        )
        .unwrap();
        assert_eq!(
            result.mappings,
            vec![
                ("app.pkg/app.pkg.Main".to_string(), "main".to_string()),
                ("app.pkg/app.pkg.Other".to_string(), "other".to_string()),
            ]
        );
        assert!(!result.mappings.iter().any(|(k, _)| k == "app.pkg"));
    }

    #[test]
    fn catalog_has_no_truncation_limit() {
        let mut xml = String::from("<resources><category title=\"Apps\"/>");
        for i in 0..=1200 {
            xml.push_str(&format!("<item drawable=\"icon_{i}\"/>"));
        }
        xml.push_str("<item drawable=\"icon_0\"/></resources>");
        let result = parse(&xml).unwrap();
        assert_eq!(result.drawables.len(), 1201);
        assert!(result.drawables.contains(&"icon_1200".to_string()));
        assert!(result.mappings.is_empty());
    }

    #[test]
    fn document_contract_requires_single_root_and_clean_prologue() {
        assert!(parse("<resources/><resources/>").is_err());
        assert!(parse("<resources/>trailing").is_err());
        assert!(parse("leading<resources/>").is_err());
        // Whitespace around the single root stays acceptable.
        assert!(parse("  <resources><item drawable=\"ok\"/></resources>\n").is_ok());
        // Attribute bounds apply to non-item elements too.
        let mut wide = String::from("<resources><group");
        for i in 0..40 {
            wide.push_str(&format!(" a{i}=\"{i}\""));
        }
        wide.push_str("/></resources>");
        assert!(parse(&wide).is_err());
    }

    #[test]
    fn mismatched_tags_are_rejected() {
        assert!(parse("<resources><item drawable=\"ok\"/></wrong>").is_err());
        assert!(parse("<resources><item drawable=\"ok\"></resources>").is_err());
        assert!(parse("<resources><item drawable=\"ok\"/>").is_err());
        // Well-formed nesting still indexes.
        let result =
            parse("<resources><group><item drawable=\"ok\"/></group></resources>").unwrap();
        assert_eq!(result.drawables, vec!["ok".to_string()]);
    }

    #[test]
    fn malformed_deep_and_doctype_documents_fail_closed() {
        assert!(parse("<resources><item").is_err());
        let deep =
            "<resources>".to_string() + &"<x>".repeat(20) + &"</x>".repeat(20) + "</resources>";
        assert!(parse(&deep).is_err());
        assert!(
            parse("<!DOCTYPE resources [<!ENTITY x SYSTEM 'file:///secret'>]><resources/>")
                .is_err()
        );
    }

    #[test]
    fn entities_decode_and_unknown_entities_fail() {
        let result = parse("<resources><item drawable=\"a&amp;b\"/></resources>").unwrap();
        // `&` is not a valid drawable character, so the item is skipped, not misread.
        assert!(result.drawables.is_empty());
        assert!(parse("<resources><item drawable=\"a&bogus;b\"/></resources>").is_err());
    }

    #[test]
    fn comments_and_cdata_are_ignored() {
        let result = parse(
            "<resources><!-- <item drawable=\"evil\"/> --><item drawable=\"ok\"/><![CDATA[<item drawable=\"evil2\"/>]]></resources>",
        )
        .unwrap();
        assert_eq!(result.drawables, vec!["ok".to_string()]);
    }
}
