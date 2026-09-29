package com.taijie.tv;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.tencent.smtt.sdk.QbSdk;
import com.tencent.smtt.sdk.TbsListener;

/**
 * 启动引导页：把内置的 X5（TBS）内核装好，再进播放页。
 *
 * 针对 WE30C（RK3229 / Android 4.4.4 / 32 位）做了三处简化：
 *   1) 不做 64 位探测——4.4 拿不到 SUPPORTED_ABIS，直接用 32 位内核 045738
 *   2) 不联网下载内核——作者自己的服务器带宽小，内置包最稳
 *   3) 带 90 秒超时保护——装不动也不会把用户卡死在引导页
 */
public class BootActivity extends AppCompatActivity {

    private static final long INSTALL_TIMEOUT = 90_000L;

    private Handler handler = new Handler();
    private TextView tip;
    private boolean jumped;

    private final Runnable timeoutTask = new Runnable() {
        @Override
        public void run() {
            tip.setText("内核安装超时，改用系统内核继续");
            goMain();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_boot);
        tip = (TextView) findViewById(R.id.bootTip);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean useX5 = prefs.getBoolean("use_x5", true);

        if (!useX5) {
            QbSdk.forceSysWebView();
            goMain();
            return;
        }

        if (QbSdk.canLoadX5(this)) {
            tip.setText("内核已就绪，正在进入…");
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    goMain();
                }
            }, 400);
            return;
        }

        if (!FileUtil.hasCoreInAssets(this)) {
            tip.setText("安装包未内置 X5 内核，改用系统内核");
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    goMain();
                }
            }, 1500);
            return;
        }

        tip.setText("正在准备 X5 内核（首次约 1-3 分钟，请勿断电）…");
        handler.post(timeoutTask);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = FileUtil.copyCoreFromAssets(BootActivity.this);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!ok) {
                            tip.setText("内核解包失败，改用系统内核");
                            goMain();
                            return;
                        }
                        installCore();
                    }
                });
            }
        }).start();
    }

    private void installCore() {
        tip.setText("正在安装 X5 内核…");
        String path = FileUtil.tbsCorePath(this);
        if (path == null) {
            goMain();
            return;
        }
        QbSdk.reset(getApplicationContext());
        QbSdk.installLocalTbsCore(getApplicationContext(), TvApp.TBS_CORE_VERSION, path);
        QbSdk.setTbsListener(new TbsListener() {
            @Override
            public void onDownloadFinish(int i) {
                Log.d(TvApp.TAG, "onDownloadFinish " + i);
            }

            @Override
            public void onDownloadProgress(int i) {
            }

            @Override
            public void onInstallFinish(int i) {
                Log.d(TvApp.TAG, "onInstallFinish " + i);
                tip.setText(i == 200 ? "内核安装完成，正在进入…" : "内核安装未完成（" + i + "），改用系统内核");
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        goMain();
                    }
                }, i == 200 ? 800 : 1500);
            }
        });
    }

    private void goMain() {
        if (jumped) {
            return;
        }
        jumped = true;
        handler.removeCallbacks(timeoutTask);
        Intent it = new Intent(this, MainActivity.class);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(it);
        finish();
    }

    @Override
    public void onBackPressed() {
        // 安装过程中不允许退出，避免装一半断电
    }
}
