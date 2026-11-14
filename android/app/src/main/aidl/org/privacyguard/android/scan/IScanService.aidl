package org.privacyguard.android.scan;

import org.privacyguard.android.scan.NativeScanResult;

interface IScanService {
    boolean initEngine(in byte[] trackerAcBytes, in byte[] modelBytes);
    NativeScanResult scanApk(in String apkPath);
}
