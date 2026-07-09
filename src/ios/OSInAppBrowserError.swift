enum OSInAppBrowserTarget {
    case externalBrowser
    case systemBrowser
    case webView
}

enum OSInAppBrowserError: Error {
    case inputArgumentsIssue(target: OSInAppBrowserTarget)
    case failedToOpen(url: String, onTarget: OSInAppBrowserTarget)
    case noBrowserToClose
    case domainNotAllowed(url: String)
    
    private var code: Int {
        return switch self {
        case .inputArgumentsIssue(let target):
            switch target {
            case .externalBrowser: 5
            case .systemBrowser: 6
            case .webView: 7
            }
        case .failedToOpen(url: _, onTarget: let target):
            switch target {
            case .externalBrowser: 8
            case .systemBrowser: 9
            case .webView: 11
            }
        case .noBrowserToClose: 12
        case .domainNotAllowed: 13
        }
    }
    
    private var description: String {
        let result: String
        
        switch self {
        case .inputArgumentsIssue(let target):             
            let targetString = switch target {
            case .externalBrowser: "openInExternalBrowser"
            case .systemBrowser: "openInSystemBrowser"
            case .webView: "openInWebView"
            }
            
            result = "The '\(targetString)' input parameters aren't valid."
        case .failedToOpen(url: let url, onTarget: let target):
            let targetString = switch target {
            case .externalBrowser: "External browser"
            case .systemBrowser: "SafariViewController"
            case .webView: "The WebView"
            }
            
            result = "\(targetString) couldn't open the following URL: '\(url)'"
        case .noBrowserToClose:
            result = "There's no browser view to close."
        case .domainNotAllowed(let url):
            result = "The URL '\(url)' cannot be opened because its domain is not in the AllowedDomains list."
        }
        
        return result
    }
    
    func toDictionary() -> [String: String] {
        [
            "code": "OS-PLUG-IABP-\(String(format: "%04d", self.code))",
            "message": self.description
        ]
    }   
}
