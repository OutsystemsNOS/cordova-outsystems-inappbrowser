import { require } from "cordova";
import { BrowserCallbacks, PluginError, SystemBrowserOptions, WebViewOptions, CallbackEvent, CallbackEventType } from "./definitions";
import { DefaultSystemBrowserOptions, DefaultWebViewOptions } from "./defaults";
var exec = require('cordova/exec')

function trigger(type: CallbackEventType, success: () => void, data?: any, onbrowserClosed: (() => void) | undefined = undefined, onbrowserPageLoaded: (() => void) | undefined = undefined, onbrowserPageNavigationCompleted: ((data?: string) => void) | undefined = undefined) {
  switch (type) {
  case CallbackEventType.SUCCESS: 
    success();
    break
  case CallbackEventType.PAGE_CLOSED:
    if (onbrowserClosed) {
      onbrowserClosed();
    }
    break;
  case CallbackEventType.PAGE_LOAD_COMPLETED:
    if (onbrowserPageLoaded) {
      onbrowserPageLoaded();
    }
    break;
  case CallbackEventType.PAGE_NAVIGATION_COMPLETED:
    if (onbrowserPageNavigationCompleted) {
      onbrowserPageNavigationCompleted(data);
    }
    break;
  default: break;
  }
}

function logErrorIfBlocked(err: PluginError): void {
  if (err && err.code === "99") {
    console.error("OSInAppBrowser: Blocked loading URL because its domain is not allowed by AllowedDomains configuration.", err);
    
    if (window.inAppBrowserSync?.triggerOnBlockedUrlEvent) {
        window.inAppBrowserSync.triggerOnBlockedUrlEvent({
            code: err.code,
            message: err.message || "Domain not allowed by AllowedDomains configuration."
        });
    }
  }
}

function openInWebView(url: string, options: WebViewOptions,  success: () => void, error: (error: PluginError) => void,  browserCallbacks?: BrowserCallbacks): void {
  options = options || DefaultWebViewOptions;
  
  let triggerCorrectCallback = function (result: string) {
    const parsedResult: CallbackEvent = JSON.parse(result);
    if (parsedResult) {
      if (browserCallbacks) {
        trigger(parsedResult.eventType, success, parsedResult.data, browserCallbacks.onbrowserClosed, browserCallbacks.onbrowserPageLoaded, browserCallbacks.onbrowserPageNavigationCompleted);
      } else {
        trigger(parsedResult.eventType, success, parsedResult.data);
      }
    }
  };

  let handleError = function (err: PluginError) {
    logErrorIfBlocked(err);
    error(err);
  };

  exec(triggerCorrectCallback, handleError, 'OSInAppBrowser', 'openInWebView', [{url, options}]);
}

function openInSystemBrowser(url: string, options: SystemBrowserOptions, success: () => void, error: (error: PluginError) => void, browserCallbacks?: BrowserCallbacks): void {
  options = options || DefaultSystemBrowserOptions;
  
  let triggerCorrectCallback = function (result: string) {
    const parsedResult: CallbackEvent = JSON.parse(result);
    if (parsedResult) {
      if (browserCallbacks) {
        trigger(parsedResult.eventType, success, parsedResult.data, browserCallbacks.onbrowserClosed, browserCallbacks.onbrowserPageLoaded);
      } else {
        trigger(parsedResult.eventType, success);
      }
    }
  };

  let handleError = function (err: PluginError) {
    logErrorIfBlocked(err);
    error(err);
  };

  exec(triggerCorrectCallback, handleError, 'OSInAppBrowser', 'openInSystemBrowser', [{url, options}]);
}

function openInExternalBrowser(url: string, success: () => void, error: (error: PluginError) => void): void {
  let handleError = function (err: PluginError) {
    logErrorIfBlocked(err);
    error(err);
  };
  exec(success, handleError, 'OSInAppBrowser', 'openInExternalBrowser', [{url}])
}

function close(success: () => void, error: (error: PluginError) => void): void {
  exec(success, error, 'OSInAppBrowser', 'close', [{}])  
}

module.exports = {
  openInWebView,
  openInExternalBrowser,
  openInSystemBrowser,
  close
}

