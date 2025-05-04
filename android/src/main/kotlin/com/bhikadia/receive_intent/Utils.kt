package com.bhikadia.receive_intent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.security.MessageDigest
import java.util.ArrayList

private const val TAG = "ReceiveIntentUtils"

fun jsonToBundle(json: JSONObject): Bundle {
    val bundle = Bundle()
    try {
        val iterator: Iterator<String> = json.keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (!json.isNull(key)) {
                val value: Any = json.get(key)
                when (value) {
                    is String -> bundle.putString(key, value)
                    is Int -> bundle.putInt(key, value)
                    is Long -> bundle.putLong(key, value)
                    is Boolean -> bundle.putBoolean(key, value)
                    is JSONObject -> bundle.putBundle(key, jsonToBundle(value))
                    is Float -> bundle.putFloat(key, value)
                    is Double -> bundle.putDouble(key, value)
                    else -> bundle.putString(key, value.toString())
                }
            }
        }
    } catch (e: JSONException) {
        Log.e(TAG, "Error converting JSON to Bundle", e)
    }
    return bundle
}

fun jsonToIntent(json: JSONObject): Intent = Intent().apply {
    putExtras(jsonToBundle(json))
}

fun bundleToJSON(bundle: Bundle): JSONObject {
    val json = JSONObject()
    val ks = bundle.keySet()
    val iterator: Iterator<String> = ks.iterator()
    while (iterator.hasNext()) {
        val key = iterator.next()
        try {
            val value = bundle.get(key)
            if (value != null) {
                Log.d(TAG, "Converting bundle key to JSON: $key")
                json.put(key, wrap(value))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error converting bundle key to JSON: $key", e)
            // Put a placeholder for the error to maintain key presence
            json.put(key, "Error: ${e.message}")
        }
    }
    return json
}

fun wrap(o: Any?): Any? {
    if (o == null) {
        return JSONObject.NULL
    }
    if (o is JSONArray || o is JSONObject) {
        return o
    }
    if (o == JSONObject.NULL) {
        return o
    }

    try {
        when (o) {
            is Collection<*> -> return toJSONArray(o)
            is Map<*, *> -> return JSONObject(o as Map<*, *>?)
            is Boolean, is Byte, is Char, is Double, is Float, 
            is Int, is Long, is Short, is String -> return o
            is Parcelable -> {
                // Improved handling for Parcelable objects - just return type info
                Log.d(TAG, "Converting Parcelable: ${o.javaClass.simpleName}")
                return "Parcelable:${o.javaClass.name}"
            }
            else -> {
                // Handle array types
                if (o.javaClass.isArray) {
                    return toJSONArray(o)
                }

                // Handle Uri and Java objects
                if (o is Uri || o.javaClass.getPackage()?.name?.startsWith("java.") == true) {
                    return o.toString()
                }

                // Default fallback
                return o.toString()
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error wrapping object: ${o?.javaClass?.simpleName}", e)
        return "Error wrapping: ${e.message}"
    }
}

@Throws(JSONException::class)
fun toJSONArray(array: Any): JSONArray {
    val result = JSONArray()

    try {
        when (array) {
            is List<*> -> {
                for (item in array) {
                    result.put(wrap(item))
                }
            }
            is Array<*> -> {
                for (item in array) {
                    result.put(wrap(item))
                }
            }
            is ByteArray -> {
                for (item in array) {
                    result.put(item)
                }
            }
            is IntArray -> {
                for (item in array) {
                    result.put(item)
                }
            }
            is LongArray -> {
                for (item in array) {
                    result.put(item)
                }
            }
            is FloatArray -> {
                for (item in array) {
                    result.put(item)
                }
            }
            is DoubleArray -> {
                for (item in array) {
                    result.put(item)
                }
            }
            is BooleanArray -> {
                for (item in array) {
                    result.put(item)
                }
            }
            is CharArray -> {
                for (item in array) {
                    result.put(item)
                }
            }
            is ShortArray -> {
                for (item in array) {
                    result.put(item)
                }
            }
            else -> {
                // Use safer reflective approach
                try {
                    Log.w(TAG, "Using reflection for array of type: ${array.javaClass.simpleName}")
                    val length = java.lang.reflect.Array.getLength(array)
                    for (i in 0 until length) {
                        result.put(wrap(java.lang.reflect.Array.get(array, i)))
                    }
                } catch (reflectionException: Exception) {
                    Log.e(TAG, "Reflection failed for array type: ${array.javaClass.simpleName}", reflectionException)
                    result.put("Error: Unsupported array type ${array.javaClass.simpleName}")
                }
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Error converting to JSONArray: ${array.javaClass.simpleName}", e)
        throw JSONException("Error converting to JSONArray: ${e.message}")
    }

    return result
}

fun getApplicationSignature(context: Context, packageName: String): List<String> {
    try {
        val signatureList: List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // New signature API for Android P (28) and above
            val packageInfo = context.packageManager.getPackageInfo(
                packageName, 
                PackageManager.GET_SIGNING_CERTIFICATES
            )

            val signingInfo = packageInfo.signingInfo
            val signers = if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }

            signers.map {
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update(it.toByteArray())
                bytesToHex(digest.digest())
            }
        } else {
            // Legacy signature API for below Android P
            @Suppress("DEPRECATION")
            val signatures = context.packageManager.getPackageInfo(
                packageName, 
                PackageManager.GET_SIGNATURES
            ).signatures

            signatures.map {
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update(it.toByteArray())
                bytesToHex(digest.digest())
            }
        }

        return signatureList
    } catch (e: Exception) {
        Log.e(TAG, "Error getting application signature for $packageName", e)
        return emptyList()
    }
}

fun bytesToHex(bytes: ByteArray): String {
    val hexArray = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F')
    val hexChars = CharArray(bytes.size * 2)
    for (j in bytes.indices) {
        val v = bytes[j].toInt() and 0xFF
        hexChars[j * 2] = hexArray[v.ushr(4)]
        hexChars[j * 2 + 1] = hexArray[v and 0x0F]
    }
    return String(hexChars)
}
