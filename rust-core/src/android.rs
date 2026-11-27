use jni::objects::{JByteArray, JLongArray, JObject, JObjectArray, JString};
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

fn string_array(
    env: &mut JNIEnv<'_>,
    values: &JObjectArray<'_>,
) -> Result<Vec<String>, crate::CoreError> {
    let length = env
        .get_array_length(values)
        .map_err(|_| crate::CoreError::Invalid("invalid JNI array"))?;
    let mut result = Vec::with_capacity(length as usize);
    for index in 0..length {
        let object = env
            .get_object_array_element(values, index)
            .map_err(|_| crate::CoreError::Invalid("invalid JNI string element"))?;
        result.push(java_string(env, &JString::from(object))?);
    }
    Ok(result)
}

fn long_array(env: &JNIEnv<'_>, values: &JLongArray<'_>) -> Result<Vec<i64>, crate::CoreError> {
    let mut result = vec![
        0;
        env.get_array_length(values)
            .map_err(|_| crate::CoreError::Invalid("invalid JNI long array"))?
            as usize
    ];
    env.get_long_array_region(values, 0, &mut result)
        .map_err(|_| crate::CoreError::Invalid("cannot read JNI long array"))?;
    Ok(result)
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

#[no_mangle]
pub extern "system" fn Java_com_locket_app_rust_RustLocketCore_mergeMomentOrderNative(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    ids: JObjectArray<'_>,
    owners: JObjectArray<'_>,
    timestamps: JLongArray<'_>,
    deleted: JObjectArray<'_>,
    hidden: JObjectArray<'_>,
) -> jni::sys::jintArray {
    let result = string_array(&mut env, &ids).and_then(|ids| {
        let owners = string_array(&mut env, &owners)?;
        let timestamps = long_array(&env, &timestamps)?;
        let deleted = string_array(&mut env, &deleted)?;
        let hidden = string_array(&mut env, &hidden)?;
        Ok(crate::feed::merge_indices(
            &ids,
            &owners,
            &timestamps,
            &deleted,
            &hidden,
        ))
    });
    match result {
        Ok(values) => match env.new_int_array(values.len() as i32) {
            Ok(array) => {
                if env.set_int_array_region(&array, 0, &values).is_ok() {
                    array.into_raw()
                } else {
                    std::ptr::null_mut()
                }
            }
            Err(_) => std::ptr::null_mut(),
        },
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", error.to_string());
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_locket_app_rust_RustLocketCore_calculateStreakNative(
    mut env: JNIEnv<'_>,
    _this: JObject<'_>,
    timestamps: JLongArray<'_>,
    now_millis: jni::sys::jlong,
    timezone: JString<'_>,
) -> jni::sys::jint {
    let result = long_array(&env, &timestamps).and_then(|values| {
        java_string(&mut env, &timezone).and_then(|timezone| {
            crate::streak::calculate(&values, now_millis, &timezone)
                .map_err(crate::CoreError::Invalid)
        })
    });
    match result {
        Ok(value) => value,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", error.to_string());
            -1
        }
    }
}
