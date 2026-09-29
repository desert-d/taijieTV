package com.taijie.tv;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.multidex.MultiDex;

import com.tencent.smtt.export.external.TbsCoreSettings;
import com.tencent.smtt.sdk.QbSdk;

import java.util.HashMap;

/**
 * 应用入口。
 * 只做两件事：MultiDex（4.4 必需）+ X5 内核的全局初始化参数。
 */
public class TvApp extends Application {

    public static final String TAG = "TaijieTV";

    /** RK3229 是 32 位 Cortex-A7，X5 内核固定用 045738（32 位）版本 */
    public static final int TBS_CORE_VERSION = 45738;
    public static final String TBS_CORE_FILE = "045738_x5.tbs.apk";

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(this);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "设备: " + Build.MODEL + " / Android " + Build.VERSION.RELEASE + " / SDK " + Build.VERSION.SDK_INT);

        HashMap<String, Object> map = new HashMap<String, Object>(2);
        map.put(TbsCoreSettings.TBS_SETTINGS_USE_SPEEDY_CLASSLOADER, true);
        map.put(TbsCoreSettings.TBS_SETTINGS_USE_DEXLOADER_SERVICE, true);
        QbSdk.initTbsSettings(map);
    }

    /**
     * 4.4 拿不到 Build.SUPPORTED_ABIS，这里按 SDK 版本判定，恒为 false（32 位）。
     * 保留这个方法是为了让代码在别的盒子上也不会选错内核版本。
     */
    public static boolean isCpu64Bit() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            String[] abis = Build.SUPPORTED_ABIS;
            if (abis != null) {
                for (String abi : abis) {
                    if (abi != null && abi.contains("64")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
