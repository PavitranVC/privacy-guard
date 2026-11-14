package org.privacyguard.android.scan;

import android.os.ParcelFileDescriptor;
import org.privacyguard.android.scan.NativeScanResult;

interface IScanService {
    NativeScanResult scanApkFd(in ParcelFileDescriptor pfd);
}
