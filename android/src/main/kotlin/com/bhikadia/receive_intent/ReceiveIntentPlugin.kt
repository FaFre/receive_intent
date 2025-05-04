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
    private var eventSink: EventSink? = null

    private lateinit var context: Context
    private var activity: Activity? = null
    private var engineInstanceId: Int = 0

    private var initialIntentMap: Map<String, Any?>? = null
    private var latestIntentMap: Map<String, Any?>? = null
    private var initialIntent = true

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

            if (initialIntent) {
                initialIntentMap = intentMap
                initialIntent = false
                Log.d(TAG, "Captured initial intent: $intentMap")
            }

            latestIntentMap = intentMap

            // Safely send event to sink if available
            eventSink?.let {
                try {
                    it.success(latestIntentMap)
                    Log.d(TAG, "Successfully sent intent to event sink")
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending intent to event sink", e)
                    it.error("INTENT_DELIVERY_ERROR", e.message, null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling intent", e)
            eventSink?.error("INTENT_HANDLING_ERROR", e.message, null)
        }
    }

    private fun setResult(result: Result, resultCode: Int?, data: String?, shouldFinish: Boolean?) {
        try {
            if (resultCode != null) {
                if (data == null) {
                    activity?.setResult(resultCode)
                } else {
                    try {
                        val json = JSONObject(data)
                        activity?.setResult(resultCode, jsonToIntent(json))
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing JSON for result", e)
                        return result.error("InvalidJson", "Failed to parse JSON: ${e.message}", null)
                    }
                }
                if (shouldFinish ?: false) {
                    activity?.finish()
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
                result.success(initialIntentMap)
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
        eventSink = events

        // Send the latest intent if available when a listener is attached
        latestIntentMap?.let { 
            events?.success(it)
            Log.d(TAG, "Sent cached intent to new listener")
        }
    }

    override fun onCancel(arguments: Any?) {
        Log.d(TAG, "Event channel listener cancelled, engine: $engineInstanceId")
        eventSink = null
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        Log.d(TAG, "Plugin detached from engine: $engineInstanceId")
        methodChannel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)

        // Clear all state to prevent leaks and ensure clean state for next attachment
        initialIntent = true
        initialIntentMap = null
        latestIntentMap = null
        eventSink = null

        // Reset engine ID
        engineInstanceId = 0
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        Log.d(TAG, "Plugin attached to activity, engine: $engineInstanceId")
        activity = binding.activity

        binding.addOnNewIntentListener(fun(intent: Intent?): Boolean {
            Log.d(TAG, "New intent received via listener, engine: $engineInstanceId")
            intent?.let { handleIntent(it, binding.activity.callingActivity?.packageName) }
            return false
        })

        // Handle the initial intent
        handleIntent(binding.activity.intent, binding.activity.callingActivity?.packageName)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        Log.d(TAG, "Plugin detached from activity for config changes, engine: $engineInstanceId")
        activity = null
        initialIntent = true  // Reset flag to capture the next intent as initial
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        Log.d(TAG, "Plugin reattached to activity after config changes, engine: $engineInstanceId")
        activity = binding.activity

        binding.addOnNewIntentListener(fun(intent: Intent?): Boolean {
            Log.d(TAG, "New intent received after config change, engine: $engineInstanceId")
            intent?.let { handleIntent(it, binding.activity.callingActivity?.packageName) }
            return false
        })

        // Handle the current intent as the initial one after config changes
        handleIntent(binding.activity.intent, binding.activity.callingActivity?.packageName)
    }

    override fun onDetachedFromActivity() {
        Log.d(TAG, "Plugin detached from activity, engine: $engineInstanceId")
        activity = null
        initialIntent = true  // Reset flag to capture the next intent as initial
    }
}
