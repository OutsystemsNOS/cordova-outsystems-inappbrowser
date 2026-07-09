package com.outsystems.plugins.inappbrowser.osinappbrowser

import com.google.gson.Gson
import com.outsystems.plugins.inappbrowser.osinappbrowserlib.OSIABEngine
import com.outsystems.plugins.inappbrowser.osinappbrowserlib.models.OSIABAnimation
import com.outsystems.plugins.inappbrowser.osinappbrowserlib.models.OSIABCustomTabsOptions
import androidx.lifecycle.lifecycleScope
import com.outsystems.plugins.inappbrowser.osinappbrowserlib.OSIABClosable
import com.outsystems.plugins.inappbrowser.osinappbrowserlib.OSIABRouter
import com.outsystems.plugins.inappbrowser.osinappbrowserlib.helpers.OSIABFlowHelper
import com.outsystems.plugins.inappbrowser.osinappbrowserlib.models.OSIABToolbarPosition
import com.outsystems.plugins.inappbrowser.osinappbrowserlib.models.OSIABViewStyle
import com.outsystems.plugins.inappbrowser.osinappbrowserlib.models.OSIABWebViewOptions
import com.outsystems.plugins.inappbrowser.osinappbrowserlib.routeradapters.OSIABCustomTabsRouterAdapter
import com.outsystems.plugins.inappbrowser.osinappbrowserlib.routeradapters.OSIABExternalBrowserRouterAdapter
import com.outsystems.plugins.inappbrowser.osinappbrowserlib.routeradapters.OSIABWebViewRouterAdapter
import org.apache.cordova.CallbackContext
import org.apache.cordova.CordovaInterface
import org.apache.cordova.CordovaPlugin
import org.apache.cordova.CordovaWebView
import org.apache.cordova.PluginResult
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import android.util.Log

class OSInAppBrowser: CordovaPlugin() {
    private var engine: OSIABEngine? = null
    private var activeRouter: OSIABRouter<Boolean>? = null
    private val gson by lazy { Gson() }

    /**
     * Comma-separated list of allowed domains read from the 'AllowedDomains' preference.
     * When empty, all domains are permitted (backwards-compatible default).
     */
    private var allowedDomains: List<String> = emptyList()

    override fun initialize(cordova: CordovaInterface, webView: CordovaWebView) {
        super.initialize(cordova, webView)
        this.engine = OSIABEngine()
        val rawPreference = preferences.getString("AllowedDomains", "").orEmpty()
        allowedDomains = rawPreference
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
    }

    override fun execute(
        action: String,
        args: JSONArray,
        callbackContext: CallbackContext
    ): Boolean {
        when(action) {
            "openInExternalBrowser" -> {
                openInExternalBrowser(args, callbackContext)
            }
            "openInSystemBrowser" -> {
                openInSystemBrowser(args, callbackContext)
            }
            "openInWebView" -> {
                openInWebView(args, callbackContext)
            }
            "close" -> {
                close(callbackContext)
            }
        }
        return true
    }

    /**
     * Calls the openExternalBrowser method of OSIABEngine to open the url in the device's browser app
     * @param args JSONArray that contains the parameters to parse (e.g. url to open)
     * @param callbackContext CallbackContext the method should return to
     */
    private fun openInExternalBrowser(args: JSONArray, callbackContext: CallbackContext) {
        val url: String?

        try {
            val argumentsDictionary = args.getJSONObject(0)
            url = argumentsDictionary.getString("url")
            if(url.isNullOrEmpty()) throw IllegalArgumentException()
        }
        catch (e: Exception) {
            sendError(callbackContext, OSInAppBrowserError.InputArgumentsIssue(OSInAppBrowserTarget.EXTERNAL_BROWSER))
            return
        }

        if (!isUrlAllowed(url)) {
            sendError(callbackContext, OSInAppBrowserError.DomainNotAllowed(url))
            return
        }

        try {
            val externalBrowserRouter = OSIABExternalBrowserRouterAdapter(cordova.context)

            engine?.openExternalBrowser(externalBrowserRouter, url) { success ->
                if (success) {
                    sendSuccess(callbackContext, OSIABEventType.SUCCESS)
                } else {
                    sendError(callbackContext, OSInAppBrowserError.OpenFailed(url, OSInAppBrowserTarget.EXTERNAL_BROWSER))
                }
            }
        }
        catch (e: Exception) {
            sendError(callbackContext, OSInAppBrowserError.OpenFailed(url, OSInAppBrowserTarget.EXTERNAL_BROWSER))
        }
    }

    /**
     * Calls the openCustomTabs method of OSIABEngine to open the url in Custom Tabs
     * @param args JSONArray that contains the parameters to parse (e.g. url to open)
     * @param callbackContext CallbackContext the method should return to
     */
    private fun openInSystemBrowser(args: JSONArray, callbackContext: CallbackContext) {
        val url: String?
        val customTabsOptions: OSIABCustomTabsOptions?

        try {
            val argumentsDictionary = args.getJSONObject(0)
            url = argumentsDictionary.getString("url")
            if(url.isNullOrEmpty()) throw IllegalArgumentException()
            customTabsOptions = buildCustomTabsOptions(argumentsDictionary.optString("options", "{}"))
        }
        catch (e: Exception) {
            sendError(callbackContext, OSInAppBrowserError.InputArgumentsIssue(OSInAppBrowserTarget.SYSTEM_BROWSER))
            return
        }

        if (!isUrlAllowed(url)) {
            sendError(callbackContext, OSInAppBrowserError.DomainNotAllowed(url))
            return
        }

        try {
            close {
                val customTabsRouter = OSIABCustomTabsRouterAdapter(
                    context = cordova.context,
                    lifecycleScope = cordova.activity.lifecycleScope,
                    options = customTabsOptions,
                    flowHelper = OSIABFlowHelper(),
                    onBrowserPageLoaded = {
                        sendSuccess(callbackContext, OSIABEventType.BROWSER_PAGE_LOADED)
                    },
                    onBrowserFinished = {
                        sendSuccess(callbackContext, OSIABEventType.BROWSER_FINISHED)
                    }
                )

                engine?.openCustomTabs(customTabsRouter, url) { success ->
                    if (success) {
                        activeRouter = customTabsRouter
                        sendSuccess(callbackContext, OSIABEventType.SUCCESS)
                    } else {
                        sendError(callbackContext, OSInAppBrowserError.OpenFailed(url, OSInAppBrowserTarget.SYSTEM_BROWSER))
                    }
                }
            }
        }
        catch (e: Exception) {
            sendError(callbackContext, OSInAppBrowserError.OpenFailed(url, OSInAppBrowserTarget.SYSTEM_BROWSER))
        }
    }

    /**
     * Calls the openWebView method of OSIABEngine to open the url in a WebView
     * @param args JSONArray that contains the parameters to parse (e.g. url to open)
     * @param callbackContext CallbackContext the method should return to
     */
    private fun openInWebView(args: JSONArray, callbackContext: CallbackContext) {
        val url: String?
        val webViewOptions: OSIABWebViewOptions?

        try {
            val argumentsDictionary = args.getJSONObject(0)
            url = argumentsDictionary.getString("url")
            if(url.isNullOrEmpty()) throw IllegalArgumentException()
            webViewOptions = buildWebViewOptions(argumentsDictionary.optString("options", "{}"))
        }
        catch (e: Exception) {
            sendError(callbackContext, OSInAppBrowserError.InputArgumentsIssue(OSInAppBrowserTarget.WEB_VIEW))
            return
        }

        if (!isUrlAllowed(url)) {
            sendError(callbackContext, OSInAppBrowserError.DomainNotAllowed(url))
            return
        }

        try {
            close {
                val webViewRouter = OSIABWebViewRouterAdapter(
                    context = cordova.context,
                    lifecycleScope = cordova.activity.lifecycleScope,
                    options = webViewOptions,
                    flowHelper = OSIABFlowHelper(),
                    onBrowserPageLoaded = {
                        sendSuccess(callbackContext, OSIABEventType.BROWSER_PAGE_LOADED)
                    },
                    onBrowserFinished = {
                        sendSuccess(callbackContext, OSIABEventType.BROWSER_FINISHED)
                    },
                    onBrowserPageNavigationCompleted = { data ->
                        sendSuccess(callbackContext, OSIABEventType.BROWSER_PAGE_NAVIGATION_COMPLETED, data)
                    }
                )

                engine?.openWebView(webViewRouter, url) { success ->
                    if (success) {
                        activeRouter = webViewRouter
                        sendSuccess(callbackContext, OSIABEventType.SUCCESS)
                    } else {
                        sendError(callbackContext, OSInAppBrowserError.OpenFailed(url, OSInAppBrowserTarget.WEB_VIEW))
                    }
                }
            }
        }
        catch (e: Exception) {
            sendError(callbackContext, OSInAppBrowserError.OpenFailed(url, OSInAppBrowserTarget.WEB_VIEW))
        }
    }

    /**
     * Calls the close method of OSIABEngine to close the currently opened view
     * @param callbackContext CallbackContext the method should return to
     */
    private fun close(callbackContext: CallbackContext) {
        close { success ->
            if (success) {
                sendSuccess(callbackContext, OSIABEventType.SUCCESS)
            } else {
                sendError(callbackContext, OSInAppBrowserError.CloseFailed)
            }
        }
    }

    private fun close(callback: (Boolean) -> Unit) {
        (activeRouter as? OSIABClosable)?.let { closableRouter ->
            closableRouter.close { success ->
                if (success) {
                    activeRouter = null
                }
                callback(success)
            }
        } ?: callback(false)
    }

    /**
     * Checks whether the given URL is allowed based on the 'AllowedDomains' preference.
     * If the allowed domains list is empty, all URLs are permitted.
     * Matching supports exact domain and subdomain (e.g. "outsystems.com" also allows "docs.outsystems.com").
     * @param url The URL string to validate.
     * @return true if the URL is allowed, false otherwise.
     */
    private fun isUrlAllowed(url: String): Boolean {
        if (allowedDomains.isEmpty()) return true
        val isAllowed = try {
            val host = URI(url).host?.lowercase() ?: return false
            allowedDomains.any { allowed ->
                host == allowed || host.endsWith(".$allowed")
            }
        } catch (e: Exception) {
            false
        }
        if (!isAllowed) {
            Log.w("OSInAppBrowser", "Access blocked to URL '$url'. The domain is not in the AllowedDomains list.")
        }
        return isAllowed
    }

    /**
     * Parses options that come in a JSObject to create a 'OSInAppBrowserSystemBrowserInputArguments' object.
     * Then, it uses the newly created object to create a 'OSIABCustomTabsOptions' object.
     * @param options The options to open the URL in the system browser (Custom Tabs) , in a JSON string.
     */
    private fun buildCustomTabsOptions(options: String): OSIABCustomTabsOptions {
        return gson.fromJson(options, OSInAppBrowserSystemBrowserInputArguments::class.java).let {
            OSIABCustomTabsOptions(
                showTitle = it.android?.showTitle ?: true,
                hideToolbarOnScroll = it.android?.hideToolbarOnScroll ?: false,
                viewStyle = it.android?.viewStyle ?: OSIABViewStyle.FULL_SCREEN,
                bottomSheetOptions = it.android?.bottomSheetOptions,
                startAnimation = it.android?.startAnimation ?: OSIABAnimation.FADE_IN,
                exitAnimation = it.android?.exitAnimation ?: OSIABAnimation.FADE_OUT
            )
        }
    }

    /**
     * Parses options that come in JSON to a 'OSInAppBrowserWebViewInputArguments'.
     * Then, it uses the newly created object to create a 'OSIABWebViewOptions' object.
     * @param options The options to open the URL in a WebView, in a JSON string.
     */
    private fun buildWebViewOptions(options: String): OSIABWebViewOptions {
        return gson.fromJson(options, OSInAppBrowserWebViewInputArguments::class.java).let {
            OSIABWebViewOptions(
                it.showURL ?: true,
                it.showToolbar ?: true,
                it.clearCache ?: true,
                it.clearSessionCache ?: true,
                it.mediaPlaybackRequiresUserAction ?: false,
                it.closeButtonText ?: "Close",
                it.toolbarPosition ?: OSIABToolbarPosition.TOP,
                it.leftToRight ?: false,
                it.showNavigationButtons ?: true,
                it.android.allowZoom ?: true,
                it.android.hardwareBack ?: true,
                it.android.pauseMedia ?: true,
                it.customWebViewUserAgent
            )
        }
    }

    /**
     * Helper method to send a success result
     * @param callbackContext CallbackContext to send the result to
     * @param event Event to be sent (SUCCESS, BROWSER_PAGE_LOADED, or BROWSER_FINISHED)
     */
    private fun sendSuccess(callbackContext: CallbackContext, event: OSIABEventType, data: Any? = null) {
        val dataToSend: Map<String, Any?> = mapOf("eventType" to event.value, "data" to data);
        val jsonString = gson.toJson(dataToSend)

        val pluginResult = PluginResult(PluginResult.Status.OK, jsonString)
        pluginResult.keepCallback = true
        callbackContext.sendPluginResult(pluginResult)
    }

    /**
     * Helper method to send an error result
     * @param callbackContext CallbackContext to send the result to
     * @param error Error to be sent in the result
     */
    private fun sendError(callbackContext: CallbackContext, error: OSInAppBrowserError) {
        val pluginResult = PluginResult(
            PluginResult.Status.ERROR,
            JSONObject().apply {
                put("code", error.code)
                put("message", error.message)
            }
        )
        callbackContext.sendPluginResult(pluginResult)
    }

}

enum class OSIABEventType(val value: Int) {
    SUCCESS(1),
    BROWSER_FINISHED(2),
    BROWSER_PAGE_LOADED(3),
    BROWSER_PAGE_NAVIGATION_COMPLETED(4)
}
