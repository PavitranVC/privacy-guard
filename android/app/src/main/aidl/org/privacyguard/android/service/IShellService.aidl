package org.privacyguard.android.service;

import org.privacyguard.android.service.ShellResult;

interface IShellService {
    ShellResult exec(in String[] cmd) = 1;
    void destroy() = 16777114;
}
