//
//  BackgroundAppInstallFlutterBridge.swift
//  Runner
//
//  Created by crc32 on 04/03/2022.
//

import Foundation
import PromiseKit
class BackgroundAppInstallFlutterBridge {
    static var shared = BackgroundAppInstallFlutterBridge()
    
    private var cachedAppInstallCallbacks: BackgroundAppInstallCallbacks? = nil
    
    func installAppNow(uri: String, appInfo: Pigeon_PbwAppInfo) -> Promise<Bool> {
        return Promise { seal in
            let appInstallData = InstallData.make(withUri: uri, appInfo: appInfo, stayOffloaded: false)
            getAppInstallCallbacks().done { appInstallCallbacks in
                guard let appInstallCallbacks = appInstallCallbacks else {
                    seal.fulfill(false)
                    return
                }
                
                appInstallCallbacks.beginAppInstallInstallData(appInstallData) { error in
                    seal.resolve(true,  error as? NSError)
                }
            }.catch { error in
                seal.reject(error)
            }
        }
    }
    
    func deleteApp(uuid: StringWrapper) -> Promise<Bool> {
        let x = Promise { seal in
            getAppInstallCallbacks().done { appInstallCallbacks in
                guard let appInstallCallbacks = appInstallCallbacks else {
                    seal.fulfill(false)
                    return
                }
                
                appInstallCallbacks.deleteAppUuid(uuid) { error in
                    seal.resolve(true, error as? NSError)
                    
                }
            }.catch { error in
                seal.reject(error)
            }
        }
        return x
        
    }
    
    private func getAppInstallCallbacks() -> Promise<BackgroundAppInstallCallbacks?> {
        return Promise { seal in
            if let cachedAppInstallCallbacks = cachedAppInstallCallbacks {
                seal.fulfill(cachedAppInstallCallbacks)
            }else {
                FlutterBackgroundController.shared.getBackgroundFlutterEngine().done { flutterEngine in
                    seal.fulfill(BackgroundAppInstallCallbacks(binaryMessenger: flutterEngine.binaryMessenger))
                }.catch { error in
                    seal.reject(error)
                }
            }
        }
        
    }
}
