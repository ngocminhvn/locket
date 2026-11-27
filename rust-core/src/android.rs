use jni::objects::{JByteArray, JObject};
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
