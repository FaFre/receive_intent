package com.bhikadia.receive_intent

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.annotation.NonNull
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.EventChannel.EventSink
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import org.json.JSONObject
import android.util.Log
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** ReceiveIntentPlugin */
class ReceiveIntentPlugin : FlutterPlugin, MethodCallHandler, EventChannel.StreamHandler, ActivityAware {
    companion object {
        private const val TAG = "ReceiveIntentPlugin"
    }

    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private lateinit var methodChannel: MethodChannel
    private lateinit var eventChannel: EventChannel

    private lateinit var context: Context
    private var activity: Activity? = null
    private var engineInstanceId: Int = 0
    private var activityBinding: ActivityPluginBinding? = null

    // Add lock for thread safety
    private val stateLock = ReentrantLock()
    private var eventSink: EventSink? = null
    private var initialIntentMap: Map<String, Any?>? = null
    private var latestIntentMap: Map<String, Any?>? = null
    private var initialIntent = true

    // Store intent listener for proper cleanup
    private var intentListener: ((Intent?) -> Boolean)? = null

    private fun handleIntent(intent: Intent, fromPackageName: String?) {
        Log.d(TAG, "Handling intent: $intent, from: $fromPackageName, engine: $engineInstanceId")
        try {
            val intentMap = mapOf<String, Any?>(
                    "fromPackageName" to fromPackageName,
                    "fromSignatures" to fromPackageName?.let { getApplicationSignature(context, it) },
                    "action" to intent.action,
                    "data" to intent.dataString,
                    "categories" to intent.categories?.toList(),
                    "extra" to intent.extras?.let { bundleToJSON(it).toString() }
            )

            stateLock.withLock {
                if (initialIntent) {
                    initialIntentMap = intentMap
                    initialIntent = false
                    Log.d(TAG, "Captured initial intent: $intentMap")
                }

                latestIntentMap = intentMap

                // Safely get event sink reference within lock
                val currentEventSink = eventSink
                if (currentEventSink != null) {
                    try {
                        currentEventSink.success(intentMap)
                        Log.d(TAG, "Successfully sent intent to event sink")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error sending intent to event sink", e)
                        currentEventSink.error("INTENT_DELIVERY_ERROR", e.message, null)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling intent", e)
            stateLock.withLock {
                eventSink?.error("INTENT_HANDLING_ERROR", e.message, null)
            }
        }
    }

    private fun setResult(result: Result, resultCode: Int?, data: String?, shouldFinish: Boolean?) {
        try {
            val currentActivity = activity
            if (resultCode != null) {
                if (currentActivity == null) {
                    return result.error("NoActivity", "No activity is available to set result", null)
                }

                if (data == null) {
                    currentActivity.setResult(resultCode)
                } else {
                    try {
                        val json = JSONObject(data)
                        currentActivity.setResult(resultCode, jsonToIntent(json))
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing JSON for result", e)
                        return result.error("InvalidJson", "Failed to parse JSON: ${e.message}", null)
                    }
                }
                if (shouldFinish == true) {
                    currentActivity.finish()
                }
                return result.success(null)
            }
            result.error("InvalidArg", "resultCode can not be null", null)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting result", e)
            result.error("SetResultError", e.message, null)
        }
    }

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        engineInstanceId = flutterPluginBinding.hashCode()
        Log.d(TAG, "Plugin attached to engine: $engineInstanceId")

        context = flutterPluginBinding.applicationContext

        methodChannel = MethodChannel(flutterPluginBinding.binaryMessenger, "receive_intent")
        methodChannel.setMethodCallHandler(this)

        eventChannel = EventChannel(flutterPluginBinding.binaryMessenger, "receive_intent/event")
        eventChannel.setStreamHandler(this)
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        Log.d(TAG, "Method call received: ${call.method}, engine: $engineInstanceId")
        when (call.method) {
            "getInitialIntent" -> {
                val intent = stateLock.withLock { initialIntentMap }
                result.success(intent)
            }
            "setResult" -> {
                setResult(result, call.argument("resultCode"), call.argument("data"), call.argument("shouldFinish"))
            }
            else -> {
                result.notImplemented()
            }
        }
    }

    override fun onListen(arguments: Any?, events: EventSink?) {
        Log.d(TAG, "Event channel listener added, engine: $engineInstanceId")

        stateLock.withLock {
            eventSink = events

            // Send the latest intent if available when a listener is attached
            latestIntentMap?.let { 
                events?.success(it)
                Log.d(TAG, "Sent cached intent to new listener")
            }
        }
    }

    override fun onCancel(arguments: Any?) {
        Log.d(TAG, "Event channel listener cancelled, engine: $engineInstanceId")
        stateLock.withLock {
            eventSink = null
        }
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        Log.d(TAG, "Plugin detached from engine: $engineInstanceId")
        methodChannel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)

        stateLock.withLock {
            // Clear all state to prevent leaks and ensure clean state for next attachment
            initialIntent = true
            initialIntentMap = null
            latestIntentMap = null
            eventSink = null
        }

        // Reset engine ID
        engineInstanceId = 0
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        Log.d(TAG, "Plugin attached to activity, engine: $engineInstanceId")
        activity = binding.activity
        activityBinding = binding

        // Create and store the intent listener for proper cleanup
        intentListener = fun(intent: Intent?): Boolean {
            Log.d(TAG, "New intent received via listener, engine: $engineInstanceId")
            intent?.let { handleIntent(it, binding.activity.callingActivity?.packageName) }
            return false
        }

        // Add the listener
        intentListener?.let { binding.addOnNewIntentListener(it) }

        // Handle the initial intent
        handleIntent(binding.activity.intent, binding.activity.callingActivity?.packageName)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        Log.d(TAG, "Plugin detached from activity for config changes, engine: $engineInstanceId")

        // Clean up the intent listener
        intentListener?.let { 
            activityBinding?.removeOnNewIntentListener(it)
        }

        activity = null
        activityBinding = null
        // Don't reset initialIntent flag during config changes
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        Log.d(TAG, "Plugin reattached to activity after config changes, engine: $engineInstanceId")
        activity = binding.activity
        activityBinding = binding

        // Create and store the intent listener
        intentListener = fun(intent: Intent?): Boolean {
            Log.d(TAG, "New intent received after config change, engine: $engineInstanceId")
            intent?.let { handleIntent(it, binding.activity.callingActivity?.packageName) }
            return false
        }

        // Add the listener
        intentListener?.let { binding.addOnNewIntentListener(it) }

        // Handle the current intent after config changes
        handleIntent(binding.activity.intent, binding.activity.callingActivity?.packageName)
    }

    override fun onDetachedFromActivity() {
        Log.d(TAG, "Plugin detached from activity, engine: $engineInstanceId")

        // Clean up the intent listener
        intentListener?.let { 
            activityBinding?.removeOnNewIntentListener(it)
            intentListener = null
        }

        activity = null
        activityBinding = null

        // Only reset initialIntent when fully detached, not during config changes
        stateLock.withLock {
            initialIntent = true
        }
    }
}
