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
 * v1.2.0 修复"一直提示 X5 内核未就绪"的四个关键点：
 *   1) initX5Environment 是异步的，装完重启后先等 8 秒让它初始化完，别一上来就判定失败重装
 *   2) 先注册 TbsListener 再调 installLocalTbsCore —— 顺序反了可能收不到安装结果
 *   3) 装完（onInstallFinish=200）当前进程的 canLoadX5 通常还是 false，
 *      必须重启进程，新进程才会加载已安装的内核
 *   4) 超时从 90 秒放宽到 4 分钟 —— RK3229 上 35MB 内核的 dexopt 可能要 2-3 分钟
 */
public class BootActivity extends AppCompatActivity {

    /** 等 initX5Environment 异步回调的时间 */
    private static final long INIT_WAIT = 8000L;
    /** RK3229 解包 + dexopt 慢，给足时间 */
    private static final long INSTALL_TIMEOUT = 4 * 60 * 1000L;
    private static final long POLL_INTERVAL = 1000L;

    private Handler handler = new Handler();
    private TextView tip;
    private boolean jumped;
    private boolean installing;
    private boolean installDone;

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            if (jumped) {
                return;
            }
            if (QbSdk.canLoadX5(BootActivity.this)) {
                TvApp.resetInstallAttempt(BootActivity.this);
                tip.setText("X5 内核已就绪，正在进入…");
                handler.removeCallbacks(startInstallTask);
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        goMain();
                    }
                }, 600);
                return;
            }
            handler.postDelayed(pollTask, POLL_INTERVAL);
        }
    };

    /** 初始化等待结束仍没加载成功 → 开始安装 */
    private final Runnable startInstallTask = new Runnable() {
        @Override
        public void run() {
            prepareAndInstall();
        }
    };

    private final Runnable timeoutTask = new Runnable() {
        @Override
        public void run() {
            tip.setText("安装超时，先用系统内核继续\n可在设置里重试安装 X5");
            goMain();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_boot);
        tip = (TextView) findViewById(R.id.bootTip);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        if (!prefs.getBoolean("use_x5", true)) {
            QbSdk.forceSysWebView();
            goMain();
            return;
        }

        if (QbSdk.canLoadX5(this)) {
            tip.setText("X5 内核已就绪，正在进入…");
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    goMain();
                }
            }, 600);
            return;
        }

        // 已经连续失败过 2 次，别再折腾，直接系统内核进播放页
        if (!TvApp.canRetryInstall(this)) {
            tip.setText("X5 内核多次安装未成功\n先用系统内核继续，可在设置里重试");
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    goMain();
                }
            }, 2500);
            return;
        }

        tip.setText("正在初始化 X5 内核…");
        handler.postDelayed(pollTask, POLL_INTERVAL);
        handler.postDelayed(startInstallTask, INIT_WAIT);
    }

    private void prepareAndInstall() {
        if (installing || jumped) {
            return;
        }
        if (!FileUtil.hasCoreInAssets(this)) {
            tip.setText("安装包未内置 X5 内核\n先用系统内核继续");
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    goMain();
                }
            }, 2500);
            return;
        }

        installing = true;
        TvApp.noteInstallAttempt(this);
        tip.setText("正在准备 X5 内核（首次约 1-3 分钟，请勿断电）…");
        handler.postDelayed(timeoutTask, INSTALL_TIMEOUT);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = FileUtil.copyCoreFromAssets(BootActivity.this);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (jumped) {
                            return;
                        }
                        if (!ok) {
                            tip.setText("内核解包失败，先用系统内核继续");
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
        final String path = FileUtil.tbsCorePath(this);
        if (path == null) {
            tip.setText("存储不可用，先用系统内核继续");
            goMain();
            return;
        }
        tip.setText("正在安装 X5 内核…");

        // 先挂监听，再触发安装
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
                Log.i(TvApp.TAG, "onInstallFinish " + i);
                if (i == 200) {
                    installDone = true;
                    tip.setText("内核安装完成，正在重启以加载内核…");
                    // 装完必须换进程才会加载；延迟一点让 dexopt 收尾
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (jumped) {
                                return;
                            }
                            if (QbSdk.canLoadX5(BootActivity.this)) {
                                TvApp.resetInstallAttempt(BootActivity.this);
                                goMain();
                            } else {
                                TvApp.restartApp(BootActivity.this);
                            }
                        }
                    }, 3000);
                } else {
                    tip.setText("内核安装未完成（代码 " + i + "）\n先用系统内核继续，可在设置里重试");
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            goMain();
                        }
                    }, 3000);
                }
            }
        });

        QbSdk.reset(getApplicationContext());
        QbSdk.installLocalTbsCore(getApplicationContext(), TvApp.TBS_CORE_VERSION, path);
    }

    private void goMain() {
        if (jumped) {
            return;
        }
        jumped = true;
        handler.removeCallbacks(timeoutTask);
        handler.removeCallbacks(pollTask);
        handler.removeCallbacks(startInstallTask);
        Intent it = new Intent(this, MainActivity.class);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(it);
        finish();
    }

    @Override
    public void onBackPressed() {
        // 装内核时不允许退出，避免装一半断电
        if (installDone || !installing) {
            super.onBackPressed();
        }
    }
}
