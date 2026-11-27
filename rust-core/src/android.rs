use jni::objects::{JByteArray, JObject, JString};
use jni::sys::jbyteArray;
use jni::JNIEnv;

#[no_mangle]
pub extern "system" fn Java_com_locket_app_rust_RustLocketCore_parseFeedNative(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    input: JByteArray<'_>,
) -> jbyteArray {
    let result = env
        .convert_byte_array(&input)
        .map_err(|_| crate::CoreError::Invalid("cannot read JNI byte array"))
        .and_then(|bytes| crate::parse_feed_response(&bytes))
        .and_then(|feed| crate::wire::encode_feed(&feed));
    match result {
        Ok(bytes) => env
            .byte_array_from_slice(&bytes)
            .map(JByteArray::into_raw)
            .unwrap_or(std::ptr::null_mut()),
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", error.to_string());
            std::ptr::null_mut()
        }
    }
}

fn java_string(env: &mut JNIEnv<'_>, value: &JString<'_>) -> Result<String, crate::CoreError> {
    env.get_string(value)
        .map(Into::into)
        .map_err(|_| crate::CoreError::Invalid("cannot read JNI string"))
}

fn return_bytes(env: &mut JNIEnv<'_>, result: Result<Vec<u8>, crate::CoreError>) -> jbyteArray {
    match result {
        Ok(bytes) => env
            .byte_array_from_slice(&bytes)
            .map(JByteArray::into_raw)
            .unwrap_or(std::ptr::null_mut()),
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", error.to_string());
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_locket_app_rust_RustLocketCore_parseHistoryNative(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    input: JByteArray<'_>,
    own_uid: JString<'_>,
    own_only: jni::sys::jboolean,
    limit: jni::sys::jint,
) -> jbyteArray {
    let result = env
        .convert_byte_array(&input)
        .map_err(|_| crate::CoreError::Invalid("cannot read JNI byte array"))
        .and_then(|bytes| {
            java_string(&mut env, &own_uid).and_then(|uid| {
                crate::parse_history_response(&bytes, &uid, own_only != 0, limit.max(1) as usize)
            })
        })
        .and_then(|page| crate::wire::encode_history(&page));
    return_bytes(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_com_locket_app_rust_RustLocketCore_parseChatNative(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    input: JByteArray<'_>,
) -> jbyteArray {
    let result = env
        .convert_byte_array(&input)
        .map_err(|_| crate::CoreError::Invalid("cannot read JNI byte array"))
        .and_then(|bytes| crate::parse_chat_response(&bytes))
        .and_then(|page| crate::wire::encode_chat(&page));
    return_bytes(&mut env, result)
}
