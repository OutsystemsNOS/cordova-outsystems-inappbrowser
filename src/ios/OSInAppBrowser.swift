import OSInAppBrowserLib
import UIKit

typealias OSInAppBrowserEngine = OSIABEngine<OSIABApplicationRouterAdapter, OSIABSafariViewControllerRouterAdapter, OSIABWebViewRouterAdapter>

/// The plugin's main class
@objc(OSInAppBrowser)
class OSInAppBrowser: CDVPlugin {
    /// The native library's main class
    private var plugin: OSInAppBrowserEngine?
    private var openedViewController: UIViewController?

    /**
     Comma-separated list of allowed domains read from the 'AllowedDomains' preference.
     When empty, all domains are permitted (backwards-compatible default).
     Matching supports exact domain and subdomains (e.g. "outsystems.com" also allows "docs.outsystems.com").
     */
    private var allowedDomains: [String] = []
    
    override func pluginInitialize() {
        self.plugin = .init()
        // Cordova normalises preference keys to lowercase
        let rawPreference = (self.commandDelegate.settings["alloweddomains"] as? String) ?? ""
        allowedDomains = rawPreference
            .split(separator: ",")
            .map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
            .filter { !$0.isEmpty }
    }
    
    @objc(openInExternalBrowser:)
    func openInExternalBrowser(command: CDVInvokedUrlCommand) {
        let target = OSInAppBrowserTarget.externalBrowser
        
        self.commandDelegate.run { [weak self] in
            guard let self else { return }
            
            guard
                let argumentsModel: OSInAppBrowserInputArgumentsSimpleModel = self.createModel(for: command.argument(at: 0)),
                let url = URL(string: argumentsModel.url)
            else {
                return self.send(error: .inputArgumentsIssue(target: target), for: command.callbackId)
            }

            guard self.isUrlAllowed(url) else {
                return self.send(error: .domainNotAllowed(url: url.absoluteString), for: command.callbackId)
            }
            
            delegateExternalBrowser(url, command.callbackId)
        }
    }
    
    @objc(openInSystemBrowser:)
    func openInSystemBrowser(command: CDVInvokedUrlCommand) {
        let target = OSInAppBrowserTarget.systemBrowser
        
        func delegateSystemBrowser(_ url: URL, _ options: OSIABSystemBrowserOptions) {
            DispatchQueue.main.async {
                self.plugin?.openSystemBrowser(url, options, { [weak self] event, viewControllerToOpen in
                    self?.handleResult(event, for: command.callbackId, checking: viewControllerToOpen, data: nil, error: .failedToOpen(url: url.absoluteString, onTarget: target))
                })
            }
        }
        
        self.commandDelegate.run { [weak self] in
            guard let self else { return }
            
            guard
                let argumentsModel: OSInAppBrowserInputArgumentsComplexModel = self.createModel(for: command.argument(at: 0)),
                let url = URL(string: argumentsModel.url)
            else {
                return self.send(error: .inputArgumentsIssue(target: target), for: command.callbackId)
            }

            guard self.isUrlAllowed(url) else {
                return self.send(error: .domainNotAllowed(url: url.absoluteString), for: command.callbackId)
            }
                        
            delegateSystemBrowser(url, argumentsModel.toSystemBrowserOptions())
        }
    }
    
    @objc(openInWebView:)
    func openInWebView(command: CDVInvokedUrlCommand) {
        let target = OSInAppBrowserTarget.webView
        
        func delegateWebView(_ url: URL, _ options: OSIABWebViewOptions) {
            DispatchQueue.main.async {
                self.plugin?.openWebView(
                    url,
                    options,
                    onDelegateClose: { [weak self] in
                        self?.viewController.dismiss(animated: true)
                    },
                    onDelegateURL: { [weak self] url in
                        self?.delegateExternalBrowser(url, command.callbackId)
                    },
                    onDelegateAlertController: { [weak self] alert in
                        self?.viewController.presentedViewController?.show(alert, sender: nil)
                    }, { [weak self] event, viewControllerToOpen, data  in
                        self?.handleResult(event, for: command.callbackId, checking: viewControllerToOpen, data: data, error: .failedToOpen(url: url.absoluteString, onTarget: target))
                    }
                )
            }
        }
        
        self.commandDelegate.run { [weak self] in
            guard let self else { return }
            
            guard
                let argumentsModel: OSInAppBrowserInputArgumentsComplexModel = self.createModel(for: command.argument(at: 0)),
                let url = URL(string: argumentsModel.url)
            else {
                return self.send(error: .inputArgumentsIssue(target: target), for: command.callbackId)
            }

            guard self.isUrlAllowed(url) else {
                return self.send(error: .domainNotAllowed(url: url.absoluteString), for: command.callbackId)
            }

            delegateWebView(url, argumentsModel.toWebViewOptions())
        }
    }
    
    @objc(close:)
    func close(command: CDVInvokedUrlCommand) {
        self.commandDelegate.run { [weak self] in
            guard let self else { return }
            
            if let openedViewController {
                DispatchQueue.main.async {
                    openedViewController.dismiss(animated: true) { [weak self] in
                        self?.sendSuccess(for: command.callbackId)
                    }
                }
            } else {
                self.send(error: .noBrowserToClose, for: command.callbackId)
            }
        }
    }
}

private extension OSInAppBrowser {
    func delegateExternalBrowser(_ url: URL, _ callbackId: String) {
        DispatchQueue.main.async {
            self.plugin?.openExternalBrowser(url, { [weak self] success in
                guard let self else { return }
                
                if success {
                    self.sendSuccess(for: callbackId)
                } else {
                    self.send(error: .failedToOpen(url: url.absoluteString, onTarget: .externalBrowser), for: callbackId)
                }
            })
        }
    }

    /**
     Checks whether the given URL is allowed based on the 'AllowedDomains' preference.
     If the allowed domains list is empty, all URLs are permitted.
     Matching supports exact domain and subdomains (e.g. "outsystems.com" also allows "docs.outsystems.com").
     - Parameter url: The URL to validate.
     - Returns: `true` if the URL is allowed, `false` otherwise.
     */
    func isUrlAllowed(_ url: URL) -> Bool {
        guard !allowedDomains.isEmpty else { return true }
        guard let host = url.host?.lowercased() else { 
            print("OSInAppBrowser WARNING: Access blocked to URL '\(url.absoluteString)'. Could not parse host.")
            return false 
        }
        let isAllowed = allowedDomains.contains { allowed in
            host == allowed || host.hasSuffix(".\(allowed)")
        }
        if !isAllowed {
            print("OSInAppBrowser WARNING: Access blocked to URL '\(url.absoluteString)'. The domain is not in the AllowedDomains list.")
        }
        return isAllowed
    }
    
    func handleResult(_ event: OSIABEventType, for callbackId: String, checking viewController: UIViewController?, data: Any?, error: OSInAppBrowserError) {
        let sendEvent: (Any?) -> Void = { data in self.sendSuccess(event, for: callbackId, data: data) }
        
        switch event {
        case .success:
            if let viewController {
                self.present(viewController) { [weak self] in
                    self?.openedViewController = viewController
                    sendEvent(data)
                }
            } else {
                self.send(error: error, for: callbackId)
            }
        case .pageClosed:
            self.openedViewController = nil
            fallthrough
        case .pageLoadCompleted:
            sendEvent(data)
        case .pageNavigationCompleted:
            sendEvent(data)
        }
    }
}

private extension OSInAppBrowser {
    func createModel<T: Decodable>(for inputArgument: Any?) -> T? {
        guard let argumentsDictionary = inputArgument as? [String: Any],
              let argumentsData = try? JSONSerialization.data(withJSONObject: argumentsDictionary),
              let argumentsModel = try? JSONDecoder().decode(T.self, from: argumentsData)
        else { return nil }
        return argumentsModel
    }
    
    func present(_ viewController: UIViewController, _ completionHandler: (() -> Void)?) {
        let showNewViewController: () -> Void = {
            self.viewController.present(viewController, animated: true, completion: completionHandler)
        }
        
        if let presentedViewController = self.viewController.presentedViewController, presentedViewController == self.openedViewController {
            presentedViewController.dismiss(animated: true, completion: showNewViewController)
        } else {
            showNewViewController()
        }
    }
    
    func sendSuccess(_ eventType: OSIABEventType? = nil, for callbackId: String, data: Any? = nil) {
        let pluginResult: CDVPluginResult
        var dataToSend = [String: Any]()

        if let eventType {
            dataToSend["eventType"] = eventType.rawValue
            dataToSend["data"] = data
            if let jsonData = try? JSONSerialization.data(withJSONObject: dataToSend, options: .prettyPrinted),
               let jsonString = String(data: jsonData, encoding: .utf8) {
                pluginResult = .init(status: .ok, messageAs: jsonString)
            } else {
                pluginResult = .init(status: .ok)
            }
        } else {
            pluginResult = .init(status: .ok)
        }
        pluginResult.keepCallback = true
        self.commandDelegate.send(pluginResult, callbackId: callbackId)
    }
    
    func send(error: OSInAppBrowserError, for callbackId: String) {
        let pluginResult = CDVPluginResult(status: .error, messageAs: error.toDictionary())
        self.commandDelegate.send(pluginResult, callbackId: callbackId)
    }
}

private extension OSInAppBrowserEngine {
    func openExternalBrowser(_ url: URL, _ completionHandler: @escaping (Bool) -> Void) {
        let router = OSIABApplicationRouterAdapter()
        self.openExternalBrowser(url, routerDelegate: router, completionHandler)
    }
    
    func openSystemBrowser(_ url: URL, _ options: OSIABSystemBrowserOptions, _ completionHandler: @escaping (OSIABEventType, UIViewController?) -> Void) {
        let router = OSIABSafariViewControllerRouterAdapter(
            options,
            onBrowserPageLoad: { completionHandler(.pageLoadCompleted, nil) },
            onBrowserClosed: { completionHandler(.pageClosed, nil) }
        )
        self.openSystemBrowser(url, routerDelegate: router) { completionHandler(.success, $0) }
    }
    
    func openWebView(
        _ url: URL,
        _ options: OSIABWebViewOptions,
        onDelegateClose: @escaping () -> Void,
        onDelegateURL: @escaping (URL) -> Void,
        onDelegateAlertController: @escaping (UIAlertController) -> Void,
        _ completionHandler: @escaping (OSIABEventType, UIViewController?, String?) -> Void
    ) {
        let callbackHandler = OSIABWebViewCallbackHandler(
            onDelegateURL: onDelegateURL,
            onDelegateAlertController: onDelegateAlertController,
            onBrowserPageLoad: { completionHandler(.pageLoadCompleted, nil, nil) },
            onBrowserClosed: { isAlreadyClosed in
                if !isAlreadyClosed {
                    onDelegateClose()
                }
                completionHandler(.pageClosed, nil, nil)
            }, onBrowserPageNavigationCompleted: { url in
                completionHandler(.pageNavigationCompleted, nil, url)
            }
        )
        let router = OSIABWebViewRouterAdapter(options, cacheManager: OSIABBrowserCacheManager(dataStore: .default()), callbackHandler: callbackHandler)
        self.openWebView(url, routerDelegate: router) { completionHandler(.success, $0, nil) }
    }
}
 
enum OSIABEventType: Int {
    case success = 1
    case pageClosed
    case pageLoadCompleted
    case pageNavigationCompleted
}
