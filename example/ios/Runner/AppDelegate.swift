import Flutter
import UIKit

@main
@objc class AppDelegate: FlutterAppDelegate {
  override func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
  ) -> Bool {
    let flutterViewController = window?.rootViewController as! FlutterViewController
    GeneratedPluginRegistrant.register(with: self)
    let navigationController = UINavigationController(rootViewController: flutterViewController)
    navigationController.navigationBar.isHidden = true
    window?.rootViewController = navigationController
    self.window.makeKeyAndVisible()
    return super.application(application, didFinishLaunchingWithOptions: launchOptions)
  }
}
