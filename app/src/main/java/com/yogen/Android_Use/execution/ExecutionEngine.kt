package com.yogen.Android_Use.execution

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.yogen.Android_Use.accessibility.AndroidUseAccessibilityService
import com.yogen.Android_Use.api.ApiClient
import com.yogen.Android_Use.api.ApiClientInterface
// Use the ActionResult from execution package
import com.yogen.Android_Use.execution.ActionResult
import com.yogen.Android_Use.models.Selector
import org.json.JSONObject

// Import constants individually, with the correct ACTION_SWIPE constant
import com.yogen.Android_Use.execution.engine_files.EngineConstants.ENGINE_TAG
import com.yogen.Android_Use.execution.engine_files.EngineConstants.ACTION_TAP_BY_SELECTOR
import com.yogen.Android_Use.execution.engine_files.EngineConstants.ACTION_INPUT_BY_SELECTOR
import com.yogen.Android_Use.execution.engine_files.EngineConstants.ACTION_COPY_BY_SELECTOR
import com.yogen.Android_Use.execution.engine_files.EngineConstants.ACTION_PASTE_BY_SELECTOR
import com.yogen.Android_Use.execution.engine_files.EngineConstants.ACTION_SELECT_BY_SELECTOR
import com.yogen.Android_Use.execution.engine_files.EngineConstants.ACTION_LONG_CLICK_BY_SELECTOR
import com.yogen.Android_Use.execution.engine_files.EngineConstants.ACTION_PERFORM_GLOBAL
import com.yogen.Android_Use.execution.engine_files.EngineConstants.ACTION_SWIPE // Corrected constant
import com.yogen.Android_Use.execution.engine_files.EngineConstants.ACTION_LAUNCH_APP
import com.yogen.Android_Use.execution.engine_files.EngineConstants.ACTION_SET_VOLUME
import com.yogen.Android_Use.execution.engine_files.EngineConstants.ACTION_DONE

// Import handlers
import com.yogen.Android_Use.execution.engine_files.ActionHandler
import com.yogen.Android_Use.execution.engine_files.handleTapBySelector
import com.yogen.Android_Use.execution.engine_files.handleInputBySelector
import com.yogen.Android_Use.execution.engine_files.handleCopyBySelector
import com.yogen.Android_Use.execution.engine_files.handlePasteBySelector
import com.yogen.Android_Use.execution.engine_files.handleSelectBySelector
import com.yogen.Android_Use.execution.engine_files.handleLongClickBySelector
import com.yogen.Android_Use.execution.engine_files.handlePerformGlobalAction
import com.yogen.Android_Use.execution.engine_files.handleSwipeSemantic
import com.yogen.Android_Use.execution.engine_files.handleLaunchApp
import com.yogen.Android_Use.execution.engine_files.handleSetVolume
import com.yogen.Android_Use.execution.engine_files.handleWait
import com.yogen.Android_Use.execution.engine_files.handleDone
import kotlinx.coroutines.runBlocking


class ExecutionEngine(
    private val context: Context,
    private var apiClient: ApiClient?
) {
    private val gson = Gson()
    val tag = ENGINE_TAG

    private val accessibilityService: AndroidUseAccessibilityService?
        get() = AndroidUseAccessibilityService.instance

    // Handler map: Key is action string, value is a suspend lambda taking JSONObject?
    // The lambda calls the specific handler (which might be suspend or not)
    private val actionHandlerMap: Map<String, suspend (JSONObject?) -> Pair<Boolean, String>> = mapOf(
        ACTION_TAP_BY_SELECTOR to { params -> parseAndCallSelectorHandler(params, ::handleTapBySelector) },
        ACTION_COPY_BY_SELECTOR to { params -> parseAndCallSelectorHandler(params, ::handleCopyBySelector) },
        ACTION_PASTE_BY_SELECTOR to { params -> parseAndCallSelectorHandler(params, ::handlePasteBySelector) },
        ACTION_LONG_CLICK_BY_SELECTOR to { params -> parseAndCallSelectorHandler(params, ::handleLongClickBySelector) },
        ACTION_INPUT_BY_SELECTOR to { params -> parseAndCallInputSelectorHandler(params) },
        ACTION_SELECT_BY_SELECTOR to { params -> parseAndCallSelectSelectorHandler(params) },

        ACTION_PERFORM_GLOBAL to { params -> handlePerformGlobalAction(accessibilityService, context, params ?: JSONObject()) },
        // Use suspend function properly
        ACTION_SWIPE to { params -> handleSwipeSemantic(accessibilityService, context, params ?: JSONObject()) },
        ACTION_LAUNCH_APP to { params -> handleLaunchApp(accessibilityService, context, params ?: JSONObject()) },
        ACTION_SET_VOLUME to { params -> handleSetVolume(accessibilityService, context, params ?: JSONObject()) },
        "wait" to { params -> handleWait(params ?: JSONObject()) },
        ACTION_DONE to { params -> 
            runBlocking { handleDone(this@ExecutionEngine, params, "dummy_correlation_id") }
        }
    )

    fun setApiClient(client: ApiClient) {
        this.apiClient = client
        Log.d(ENGINE_TAG, "ApiClient set in ExecutionEngine")
    }

    // Helper to parse selector and call the appropriate handler
    private suspend fun parseAndCallSelectorHandler(
        params: JSONObject?,
        // The handler function itself takes the Selector
        handler: suspend (AndroidUseAccessibilityService?, Context, Selector) -> Pair<Boolean, String>
    ): Pair<Boolean, String> {
        return try {
            val selectorJson = params?.optJSONObject("selector")?.toString()
                ?: return Pair(false, "Missing 'selector' parameter object.")
            val selector = gson.fromJson(selectorJson, Selector::class.java)
                ?: return Pair(false, "Failed to parse 'selector' parameter object.")
            // Call the specific handler function
            handler(accessibilityService, context, selector)
        } catch (e: JsonSyntaxException) {
            Log.e(ENGINE_TAG, "JSON parsing error for selector: ${e.message}")
            Pair(false, "Invalid JSON format for 'selector' parameter.")
        } catch (e: Exception) {
            Log.e(ENGINE_TAG, "Error parsing/calling selector handler: ${e.message}")
            Pair(false, "Error processing selector parameter: ${e.message}")
        }
    }

    // Helper for input_by_selector
    private suspend fun parseAndCallInputSelectorHandler(params: JSONObject?): Pair<Boolean, String> {
         return try {
            val selectorJson = params?.optJSONObject("selector")?.toString()
            val textToType = params?.optString("text_to_type")
             if (selectorJson == null || textToType == null) {
                return Pair(false, "Missing 'selector' or 'text_to_type' parameter.")
            }
            val selector = gson.fromJson(selectorJson, Selector::class.java)
                ?: return Pair(false, "Failed to parse 'selector' parameter object.")
            // Call the specific handler function
            handleInputBySelector(accessibilityService, context, selector, textToType)
        } catch (e: JsonSyntaxException) {
            Log.e(ENGINE_TAG, "JSON parsing error for input selector: ${e.message}")
            Pair(false, "Invalid JSON format for parameters in input action.")
        } catch (e: Exception) {
            Log.e(ENGINE_TAG, "Error parsing/calling input selector handler: ${e.message}")
            Pair(false, "Error processing input selector parameters: ${e.message}")
        }
    }

     // Helper for select_by_selector
    private suspend fun parseAndCallSelectSelectorHandler(params: JSONObject?): Pair<Boolean, String> {
        return try {
            val selectorJson = params?.optJSONObject("selector")?.toString()
                ?: return Pair(false, "Missing 'selector' parameter object.")
            val selector = gson.fromJson(selectorJson, Selector::class.java)
                ?: return Pair(false, "Failed to parse 'selector' parameter object.")
            val start = params.optInt("start", -1).takeIf { it >= 0 }
            val end = params.optInt("end", -1).takeIf { it >= 0 }
            // Call the specific handler function
            handleSelectBySelector(accessibilityService, context, selector, start, end)
        } catch (e: JsonSyntaxException) {
            Log.e(ENGINE_TAG, "JSON parsing error for select selector: ${e.message}")
            Pair(false, "Invalid JSON format for parameters in select action.")
        } catch (e: Exception) {
            Log.e(ENGINE_TAG, "Error parsing/calling select selector handler: ${e.message}")
            Pair(false, "Error processing select selector parameters: ${e.message}")
        }
    }


    suspend fun processCommand(actionType: String, commandParams: JSONObject?, correlationId: String) {
        val logParams = if (actionType == ACTION_INPUT_BY_SELECTOR) "(params contain text)" else commandParams?.toString()
        Log.i(ENGINE_TAG, "[$correlationId] Processing command: '$actionType' with params: $logParams")

        val service = accessibilityService
        val handlerWrapper = actionHandlerMap[actionType]
        val currentApiClient = apiClient

        if (currentApiClient == null) {
            Log.e(ENGINE_TAG, "[$correlationId] ApiClient is null! Cannot process command '$actionType'.")
            return
        }

        if (handlerWrapper == null) {
            Log.e(ENGINE_TAG, "[$correlationId] Unsupported action type: '$actionType'")
            // Use the ActionResult from execution package
            val result = ActionResult(success = false, message = "Unsupported action: $actionType")
            currentApiClient.sendExecutionResult(result, correlationId)
            return
        }

        // Define which actions require the service using correct constants
        val serviceRequiredActions = setOf(
            ACTION_TAP_BY_SELECTOR, ACTION_INPUT_BY_SELECTOR, ACTION_COPY_BY_SELECTOR,
            ACTION_PASTE_BY_SELECTOR, ACTION_SELECT_BY_SELECTOR, ACTION_LONG_CLICK_BY_SELECTOR,
            ACTION_PERFORM_GLOBAL, ACTION_SWIPE // Correct constant name
        )

        if (actionType in serviceRequiredActions && service == null) {
            Log.e(ENGINE_TAG, "[$correlationId] AccessibilityService needed for action '$actionType' but unavailable.")
            val result = ActionResult(success = false, message = "Service not connected for action '$actionType'.")
            currentApiClient.sendExecutionResult(result, correlationId)
            return
        }

        try {
            // Special handling for done action
            if (actionType == ACTION_DONE) {
                handleDone(this, commandParams, correlationId)
                return
            }
            
            // Execute the handler wrapper lambda from the map
            val (success, message) = handlerWrapper(commandParams)
            Log.i(ENGINE_TAG, "[$correlationId] Action '$actionType' execution result: Success=$success, Message='$message'")
            val result = ActionResult(success = success, message = message)
            currentApiClient.sendExecutionResult(result, correlationId)
        } catch (e: Exception) {
            Log.e(ENGINE_TAG, "[$correlationId] Exception executing action '$actionType'", e)
            val result = ActionResult(success = false, message = "Client exception during $actionType: ${e.message}")
            currentApiClient.sendExecutionResult(result, correlationId)
        }
    }

    // This function needs to exist if handleDone is calling it.
    // Ensure it matches the signature used in handleDone.
    internal fun sendResult(actionResult: ActionResult, correlationId: String) {
        Log.d(ENGINE_TAG, "ExecutionEngine sending result via internal method for correlationId: $correlationId")
        // Assuming apiClient might be null, handle it safely
        apiClient?.sendExecutionResult(actionResult, correlationId)
            ?: Log.e(ENGINE_TAG, "Attempted to send result but apiClient was null.")
    }
}