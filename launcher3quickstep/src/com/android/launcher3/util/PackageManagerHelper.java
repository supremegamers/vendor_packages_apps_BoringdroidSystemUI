/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3.util;

import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.os.PatternMatcher;

/**
 * Utility methods using package manager
 */
public class PackageManagerHelper {

    public PackageManagerHelper() {
    }

    /**
     * Returns whether an application is suspended as per
     * {@link android.app.admin.DevicePolicyManager#isPackageSuspended}.
     */
    public static boolean isAppSuspended(ApplicationInfo info) {
        return (info.flags & ApplicationInfo.FLAG_SUSPENDED) != 0;
    }

    /**
     * Creates an intent filter to listen for actions with a specific package in the data field.
     */
    public static IntentFilter getPackageFilter(String pkg, String... actions) {
        IntentFilter packageFilter = new IntentFilter();
        for (String action : actions) {
            packageFilter.addAction(action);
        }
        packageFilter.addDataScheme("package");
        packageFilter.addDataSchemeSpecificPart(pkg, PatternMatcher.PATTERN_LITERAL);
        return packageFilter;
    }
}
