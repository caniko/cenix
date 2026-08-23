#![forbid(unsafe_code)]

use serde::{Deserialize, Serialize};

pub const PROTOCOL_VERSION: u32 = 1;
pub const MAX_MESSAGE_BYTES: usize = 1_048_576;
pub const MAX_APPLICATIONS: usize = 10_000;
pub const MAX_LABEL_CHARS: usize = 256;
pub const MAX_IDENT_CHARS: usize = 256;
pub const MAX_QUERY_CHARS: usize = 256;
pub const MAX_VISIBLE_PROFILES: usize = 64;

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct FilterRequest {
    pub protocol_version: u32,
    pub request_id: String,
    pub query: String,
    pub visible_profile_ids: Vec<u64>,
    pub applications: Vec<Application>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Application {
    pub package: String,
    pub class: String,
    pub profile_id: u64,
    pub label: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ComponentIdentity {
    pub package: String,
    pub class: String,
    pub profile_id: u64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct FilterResponse {
    pub protocol_version: u32,
    pub request_id: String,
    pub ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub matches: Option<Vec<ComponentIdentity>>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<ProtocolError>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ProtocolError {
    pub code: String,
    pub message: String,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq, Ord, PartialOrd)]
enum Rank {
    Exact = 0,
    Prefix = 1,
    TokenPrefix = 2,
    Substring = 3,
}

pub fn filter_and_order(bytes: &[u8]) -> Vec<u8> {
    encode(&filter_and_order_result(bytes).unwrap_or_else(|response| response))
}

pub fn filter_and_order_result(bytes: &[u8]) -> Result<FilterResponse, FilterResponse> {
    if bytes.len() > MAX_MESSAGE_BYTES {
        return Err(error_response(
            String::new(),
            "BOUNDS",
            "request exceeds maximum message size",
        ));
    }
    let request: FilterRequest = serde_json::from_slice(bytes)
        .map_err(|_| error_response(String::new(), "MALFORMED", "request is not valid JSON"))?;
    if request.protocol_version != PROTOCOL_VERSION {
        return Err(error_response(
            request.request_id,
            "PROTOCOL_VERSION",
            "unsupported protocol version",
        ));
    }
    if let Err((code, message)) = validate(&request) {
        return Err(error_response(request.request_id, code, message));
    }
    Ok(FilterResponse {
        protocol_version: PROTOCOL_VERSION,
        request_id: request.request_id.clone(),
        ok: true,
        matches: Some(rank_apps(&request)),
        error: None,
    })
}

pub fn error_response(request_id: String, code: &str, message: &str) -> FilterResponse {
    FilterResponse {
        protocol_version: PROTOCOL_VERSION,
        request_id,
        ok: false,
        matches: None,
        error: Some(ProtocolError {
            code: code.to_string(),
            message: message.to_string(),
        }),
    }
}

pub fn encode(response: &FilterResponse) -> Vec<u8> {
    serde_json::to_vec(response).unwrap_or_else(|_| {
        br#"{"protocol_version":1,"request_id":"","ok":false,"error":{"code":"ENCODE","message":"response encode failed"}}"#.to_vec()
    })
}

fn validate(request: &FilterRequest) -> Result<(), (&'static str, &'static str)> {
    if request.applications.len() > MAX_APPLICATIONS {
        return Err(("BOUNDS", "too many applications"));
    }
    if request.visible_profile_ids.len() > MAX_VISIBLE_PROFILES {
        return Err(("BOUNDS", "too many visible profiles"));
    }
    if request.query.chars().count() > MAX_QUERY_CHARS {
        return Err(("BOUNDS", "query exceeds maximum length"));
    }
    for app in &request.applications {
        if app.package.chars().count() > MAX_IDENT_CHARS
            || app.class.chars().count() > MAX_IDENT_CHARS
        {
            return Err(("BOUNDS", "package or class exceeds maximum length"));
        }
        if app.label.chars().count() > MAX_LABEL_CHARS {
            return Err(("BOUNDS", "label exceeds maximum length"));
        }
        if app.package.is_empty() || app.class.is_empty() {
            return Err(("MALFORMED", "package and class are required"));
        }
    }
    Ok(())
}

fn rank_apps(request: &FilterRequest) -> Vec<ComponentIdentity> {
    let query = normalize(&request.query);
    let mut scored: Vec<(Rank, String, &Application)> = request
        .applications
        .iter()
        .filter(|app| request.visible_profile_ids.contains(&app.profile_id))
        .filter_map(|app| {
            let label = normalize(&app.label);
            let rank = if query.is_empty() {
                Some(Rank::Exact)
            } else {
                rank_label(&label, &query)
            };
            rank.map(|rank| (rank, label, app))
        })
        .collect();
    scored.sort_by(|a, b| {
        a.0.cmp(&b.0)
            .then_with(|| a.1.cmp(&b.1))
            .then_with(|| a.2.package.cmp(&b.2.package))
            .then_with(|| a.2.class.cmp(&b.2.class))
            .then_with(|| a.2.profile_id.cmp(&b.2.profile_id))
    });
    scored
        .into_iter()
        .map(|(_, _, app)| ComponentIdentity {
            package: app.package.clone(),
            class: app.class.clone(),
            profile_id: app.profile_id,
        })
        .collect()
}

fn rank_label(label: &str, query: &str) -> Option<Rank> {
    if label == query {
        return Some(Rank::Exact);
    }
    if label.starts_with(query) {
        return Some(Rank::Prefix);
    }
    if label
        .split_whitespace()
        .any(|token| token.starts_with(query))
    {
        return Some(Rank::TokenPrefix);
    }
    if label.contains(query) {
        return Some(Rank::Substring);
    }
    None
}

fn normalize(value: &str) -> String {
    value
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .to_lowercase()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn app(package: &str, class: &str, profile: u64, label: &str) -> Application {
        Application {
            package: package.to_string(),
            class: class.to_string(),
            profile_id: profile,
            label: label.to_string(),
        }
    }

    fn request(query: &str, profiles: &[u64], applications: Vec<Application>) -> FilterRequest {
        FilterRequest {
            protocol_version: PROTOCOL_VERSION,
            request_id: "req-1".into(),
            query: query.into(),
            visible_profile_ids: profiles.to_vec(),
            applications,
        }
    }

    fn names(response: FilterResponse) -> Vec<String> {
        response
            .matches
            .unwrap()
            .into_iter()
            .map(|item| item.package)
            .collect()
    }

    #[test]
    fn empty_query_returns_visible_apps_sorted() {
        let req = request(
            "",
            &[0],
            vec![
                app("b.pkg", "B", 0, "Bravo"),
                app("a.pkg", "A", 0, "Alpha"),
                app("hidden.pkg", "H", 1, "Hidden"),
            ],
        );
        let bytes = serde_json::to_vec(&req).unwrap();
        let response = filter_and_order_result(&bytes).unwrap();
        assert_eq!(names(response), ["a.pkg", "b.pkg"]);
    }

    #[test]
    fn exact_match_outranks_prefix() {
        let req = request(
            "mail",
            &[0],
            vec![
                app("prefix.pkg", "P", 0, "Mailbox"),
                app("exact.pkg", "E", 0, "Mail"),
            ],
        );
        let bytes = serde_json::to_vec(&req).unwrap();
        let response = filter_and_order_result(&bytes).unwrap();
        assert_eq!(names(response), ["exact.pkg", "prefix.pkg"]);
    }

    #[test]
    fn prefix_and_token_and_substring() {
        let req = request(
            "cam",
            &[0],
            vec![
                app("sub.pkg", "S", 0, "My Camera"),
                app("pre.pkg", "P", 0, "Camera"),
                app("tok.pkg", "T", 0, "Open Camera"),
                app("mid.pkg", "M", 0, "Webcam"),
            ],
        );
        let bytes = serde_json::to_vec(&req).unwrap();
        let response = filter_and_order_result(&bytes).unwrap();
        assert_eq!(
            names(response),
            ["pre.pkg", "sub.pkg", "tok.pkg", "mid.pkg"]
        );
    }

    #[test]
    fn deterministic_ties_use_package_then_class() {
        let req = request(
            "same",
            &[0],
            vec![
                app("z.pkg", "Z", 0, "Same"),
                app("a.pkg", "B", 0, "Same"),
                app("a.pkg", "A", 0, "Same"),
            ],
        );
        let bytes = serde_json::to_vec(&req).unwrap();
        let first = names(filter_and_order_result(&bytes).unwrap());
        let second = names(filter_and_order_result(&bytes).unwrap());
        assert_eq!(first, ["a.pkg", "a.pkg", "z.pkg"]);
        assert_eq!(first, second);
    }

    #[test]
    fn hidden_profiles_are_excluded() {
        let req = request(
            "",
            &[0],
            vec![
                app("vis.pkg", "V", 0, "Visible"),
                app("hid.pkg", "H", 9, "Hidden"),
            ],
        );
        let bytes = serde_json::to_vec(&req).unwrap();
        assert_eq!(names(filter_and_order_result(&bytes).unwrap()), ["vis.pkg"]);
    }

    #[test]
    fn oversized_message_is_rejected() {
        let bytes = vec![b'{'; MAX_MESSAGE_BYTES + 1];
        let err = filter_and_order_result(&bytes).unwrap_err();
        assert_eq!(err.error.unwrap().code, "BOUNDS");
    }

    #[test]
    fn oversized_query_is_rejected() {
        let req = request(&"q".repeat(MAX_QUERY_CHARS + 1), &[0], vec![]);
        let bytes = serde_json::to_vec(&req).unwrap();
        let err = filter_and_order_result(&bytes).unwrap_err();
        assert_eq!(err.error.unwrap().code, "BOUNDS");
    }

    #[test]
    fn malformed_request_is_rejected() {
        let err = filter_and_order_result(b"not-json").unwrap_err();
        assert_eq!(err.error.unwrap().code, "MALFORMED");
    }

    #[test]
    fn unsupported_protocol_version_is_rejected() {
        let mut req = request("", &[0], vec![]);
        req.protocol_version = 99;
        let bytes = serde_json::to_vec(&req).unwrap();
        let err = filter_and_order_result(&bytes).unwrap_err();
        assert_eq!(err.error.unwrap().code, "PROTOCOL_VERSION");
    }
}
