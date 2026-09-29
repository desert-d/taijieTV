package com.taijie.tv;

import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.util.Log;

import androidx.multidex.MultiDex;

import com.tencent.smtt.export.external.TbsCoreSettings;
import com.tencent.smtt.sdk.QbSdk;

import java.io.File;
import java.util.HashMap;

/**
 * 应用入口。
 *
 * X5 内核要点（这是 v1.2.0 修复"X5内核未就绪"的关键）：
 *   1) 必须在 Application 里调 QbSdk.initX5Environment()，内核才会真正被加载进进程。
 *      只调 installLocalTbsCore() 只是"把文件装进去"，不加载，canLoadX5() 会一直 false。
 *   2) initTbsSettings 的两个开关要配：快速类加载 + dexopt 独立进程（Manifest 里已声明 :dexopt 服务）。
 */
public class TvApp extends Application {

    public static final String TAG = "TaijieTV";

    /** RK3229 是 32 位 Cortex-A7，X5 内核固定用 045738（32 位）版本 */
    public static final int TBS_CORE_VERSION = 45738;
    public static final String TBS_CORE_FILE = "045738_x5.tbs.apk";

    /** initX5Environment 的回调结果：X5 内核是否已加载成功 */
    private static volatile boolean x5Inited = false;

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

        // 真正触发 X5 内核初始化（异步，结果在回调里）
        QbSdk.initX5Environment(getApplicationContext(), new QbSdk.PreInitCallback() {
            @Override
            public void onCoreInitFinished() {
                Log.i(TAG, "X5 onCoreInitFinished");
            }

            @Override
            public void onViewInitFinished(boolean coreInited) {
                x5Inited = coreInited;
                Log.i(TAG, "X5 onViewInitFinished coreInited=" + coreInited
                        + " version=" + QbSdk.getTbsVersion(getApplicationContext()));
            }
        });
    }

    public static boolean isX5Inited() {
        return x5Inited;
    }

    /**
     * 4.4 拿不到 Build.SUPPORTED_ABIS，这里按 SDK 版本判定，恒为 false（32 位）。
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

    /* ---------- 安装重试保护：避免"主界面↔引导页"无限跳转 ---------- */

    public static boolean canRetryInstall(Context c) {
        return c.getSharedPreferences("tv", MODE_PRIVATE).getInt("x5_attempt", 0) < 2;
    }

    public static void noteInstallAttempt(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("tv", MODE_PRIVATE);
        p.edit().putInt("x5_attempt", p.getInt("x5_attempt", 0) + 1).apply();
    }

    public static void resetInstallAttempt(Context c) {
        c.getSharedPreferences("tv", MODE_PRIVATE).edit().putInt("x5_attempt", 0).apply();
    }

    /** 安装完内核后必须重启进程，新进程才会加载已安装的内核 */
    public static void restartApp(Context c) {
        Intent it = new Intent(c, BootActivity.class);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, 9527, it, PendingIntent.FLAG_CANCEL_CURRENT);
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.set(AlarmManager.RTC, System.currentTimeMillis() + 300, pi);
        }
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    /* ---------- 诊断信息 ---------- */

    public static String freeSpaceText() {
        try {
            StatFs sf = new StatFs(Environment.getDataDirectory().getPath());
            long blocks = sf.getAvailableBlocks();
            long size = sf.getBlockSize();
            return (blocks * size) / 1024 / 1024 + " MB";
        } catch (Exception e) {
            return "未知";
        }
    }

    public static String coreInfo(Context c) {
        StringBuilder sb = new StringBuilder();
        boolean loaded = QbSdk.canLoadX5(c);
        sb.append("内核状态：").append(loaded ? "X5 已加载，版本 " + QbSdk.getTbsVersion(c) : "未加载");
        sb.append("\n系统：").append(Build.MODEL).append(" / Android ").append(Build.VERSION.RELEASE);
        sb.append("\n架构：").append(isCpu64Bit() ? "64 位" : "32 位");
        String path = FileUtil.tbsCorePath(c);
        sb.append("\n内核包路径：").append(path == null ? "不可用" : path);
        if (path != null) {
            File f = new File(path);
            sb.append("\n内核包大小：").append(f.exists() ? (f.length() / 1024 / 1024) + " MB" : "文件不存在");
        }
        sb.append("\n内置内核：").append(FileUtil.hasCoreInAssets(c) ? "有" : "无（会尝试联网下载）");
        sb.append("\n剩余存储：").append(freeSpaceText());
        return sb.toString();
    }
}
