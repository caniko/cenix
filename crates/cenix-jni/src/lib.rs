use std::panic::{self, AssertUnwindSafe};

use cenix_core::{MAX_MESSAGE_BYTES, encode, error_response, filter_and_order};
use jni::JNIEnv;
use jni::objects::{JByteArray, JClass};
use jni::sys::jbyteArray;

pub fn handle_request(bytes: &[u8]) -> Vec<u8> {
    if bytes.len() > MAX_MESSAGE_BYTES {
        return encode(&error_response(
            String::new(),
            "BOUNDS",
            "request exceeds maximum message size",
        ));
    }
    with_panic_boundary(|| filter_and_order(bytes))
}

pub fn with_panic_boundary<F>(work: F) -> Vec<u8>
where
    F: FnOnce() -> Vec<u8> + panic::UnwindSafe,
{
    panic::catch_unwind(AssertUnwindSafe(work)).unwrap_or_else(|_| {
        encode(&error_response(
            String::new(),
            "PANIC",
            "native filter panicked",
        ))
    })
}

/// # Safety
/// JNI entrypoint. `request` must be a valid `jbyteArray` or null.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_caniko_cenix_NativeBridge_filterAndOrderApps(
    env: JNIEnv,
    _class: JClass,
    request: JByteArray,
) -> jbyteArray {
    let result = panic::catch_unwind(AssertUnwindSafe(|| dispatch(env, request)));
    match result {
        Ok(array) => array.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn dispatch<'a>(env: JNIEnv<'a>, request: JByteArray<'a>) -> JByteArray<'a> {
    let bytes = match env.convert_byte_array(&request) {
        Ok(bytes) => bytes,
        Err(_) => encode(&error_response(
            String::new(),
            "MALFORMED",
            "native request was null or unreadable",
        )),
    };
    let response = handle_request(&bytes);
    env.byte_array_from_slice(&response)
        .unwrap_or_else(|_| env.new_byte_array(0).unwrap_or(request))
}

#[cfg(test)]
mod tests {
    use super::*;
    use cenix_core::{FilterRequest, PROTOCOL_VERSION};

    #[test]
    fn malformed_bytes_return_error_envelope() {
        let response: cenix_core::FilterResponse =
            serde_json::from_slice(&handle_request(b"{")).unwrap();
        assert!(!response.ok);
        assert_eq!(response.error.unwrap().code, "MALFORMED");
    }

    #[test]
    fn oversized_request_is_rejected_before_parse() {
        let response: cenix_core::FilterResponse =
            serde_json::from_slice(&handle_request(&vec![b'x'; MAX_MESSAGE_BYTES + 8])).unwrap();
        assert_eq!(response.error.unwrap().code, "BOUNDS");
    }

    #[test]
    fn protocol_mismatch_is_structured() {
        let request = FilterRequest {
            protocol_version: 7,
            request_id: "x".into(),
            query: String::new(),
            visible_profile_ids: vec![0],
            applications: vec![],
        };
        let response: cenix_core::FilterResponse =
            serde_json::from_slice(&handle_request(&serde_json::to_vec(&request).unwrap()))
                .unwrap();
        assert_eq!(response.error.unwrap().code, "PROTOCOL_VERSION");
        assert_eq!(response.request_id, "x");
        assert_eq!(response.protocol_version, PROTOCOL_VERSION);
    }

    #[test]
    fn panic_boundary_does_not_unwind() {
        let response: cenix_core::FilterResponse =
            serde_json::from_slice(&with_panic_boundary(|| panic!("boom"))).unwrap();
        assert_eq!(response.error.unwrap().code, "PANIC");
    }
}
