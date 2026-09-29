package com.taijie.tv;

import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.tencent.smtt.export.external.extension.interfaces.IX5WebSettingsExtension;
import com.tencent.smtt.sdk.QbSdk;
import com.tencent.smtt.sdk.ValueCallback;
import com.tencent.smtt.sdk.WebChromeClient;
import com.tencent.smtt.sdk.WebSettings;
import com.tencent.smtt.sdk.WebView;
import com.tencent.smtt.sdk.WebViewClient;

/**
 * 播放主页面。
 *
 * 为泰捷 WE30C（RK3229 四核 A7 / Mali-400 / 1GB 内存 / Android 4.4.4）做的针对性设计：
 *   1) 单 WebView：原版双缓冲在 1GB 内存上会频繁 OOM，这里只留一个
 *   2) 缩放封顶 100%：4K 输出时按 200% 渲染会拖垮 Mali-400
 *   3) 全部 JS 用 ES5 写法（不用箭头函数），X5 与 4.4 自带内核都能解析
 *   4) 双源自动回落：主源 12 秒不出画面就切备用源
 *   5) 默认标清 480P：弱机 + 100M 网口下最稳，可在菜单里实时切换
 */
public class MainActivity extends AppCompatActivity {

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36";

    /** 瘦身脚本：清空图片与无关栏目块，最多跑 60 轮（约 1.2 秒）自动停 */
    private static final String JS_TRIM =
            "(function(){var n=0;var s=function(){n++;"
                    + "if(document.querySelector('video')||n>60)return;"
                    + "var im=document.images;for(var i=0;i<im.length;i++){im[i].src='';}"
                    + "var cl=['column_wrapper','newmap','newtopbz','newtopbzTV'];"
                    + "for(var j=0;j<cl.length;j++){var e=document.getElementsByClassName(cl[j]);"
                    + "for(var k=0;k<e.length;k++){e[k].innerHTML='';}}"
                    + "setTimeout(s,20);};s();})()";

    /** 播放脚本：自动点全屏、自动播放、音量拉满，最多尝试 100 轮 */
    private static final String JS_PLAY =
            "(function(){var n=0;var t=function(){n++;"
                    + "var v=document.querySelector('video');"
                    + "var b=document.querySelector('#player_pagefullscreen_yes_player')||document.querySelector('.videoFull');"
                    + "if(b&&!window.__fs){window.__fs=1;try{b.click()}catch(e){}}"
                    + "if(v){try{v.volume=1;v.play()}catch(e){}}"
                    + "if((v&&v.readyState>0)||n>100)return;"
                    + "setTimeout(t,30);};t();})()";

    /** 播放/暂停 */
    private static final String JS_TOGGLE =
            "(function(){var v=document.querySelector('video');if(!v)return;"
                    + "if(v.paused){v.play()}else{v.pause()}})()";

    /** 全屏切换 */
    private static final String JS_FULL =
            "(function(){var b=document.querySelector('#player_pagefullscreen_yes_player')"
                    + "||document.querySelector('.videoFull');if(b){window.__fs=0;try{b.click()}catch(e){}}})()";

    /**
     * 清晰度切换。q：1流畅 2标清 3高清 4超清（0 = 跟随网页默认，不干预）。
     * 三级策略：央视网精确 id → 选项文字匹配 → 展开清晰度面板后再选。
     * 两个站的选项文字不同：央视网是「标清/高清/超清」，央视频是「540P/720P/1080P/蓝光」。
     */
    private static final String JS_QUALITY =
            "(function(q){"
                    + "if(!q)return;"
                    + "var T=[[],['360'],['480','540'],['720'],['1080']];"
                    + "var W=[[],['\\u6d41\\u7545','360P'],"
                    + "['\\u6807\\u6e05','480P','540P'],"
                    + "['\\u9ad8\\u6e05','720P'],"
                    + "['\\u8d85\\u6e05','1080P','\\u84dd\\u5149']];"
                    + "var P=T[q],K=W[q];"
                    + "if(!P||!K)return;"
                    + "function byId(){for(var i=0;i<P.length;i++){"
                    + "var e=document.getElementById('resolution_item_'+P[i]+'_player');"
                    + "if(e){e.click();return 1}}return 0};"
                    + "function byText(){var a=document.querySelectorAll('li,span,div,a,button,em,i');"
                    + "for(var i=0;i<a.length;i++){var t=(a[i].innerText||'').replace(/\\s/g,'');"
                    + "if(!t||t.length>6)continue;"
                    + "for(var j=0;j<K.length;j++){if(t===K[j]||t.indexOf(K[j])===0){a[i].click();return 1}}}"
                    + "return 0};"
                    + "if(byId())return;"
                    + "if(byText())return;"
                    + "var b=document.querySelectorAll('li,span,div,a,button,em,i,p');"
                    + "for(var i=0;i<b.length;i++){var t=(b[i].innerText||'').replace(/\\s/g,'');"
                    + "if(t==='\\u6e05\\u6670\\u5ea6'||t==='\\u753b\\u8d28'||t==='\\u7801\\u7387'"
                    + "||t==='\\u81ea\\u52a8'||t.indexOf('\\u6e05\\u6670\\u5ea6')===0){"
                    + "b[i].click();setTimeout(byText,700);return}}"
                    + "})(__Q__)";

    /** 节目单：央视网取 #jiemu 当前项与下一项，央视频取 .tvSelectJiemu */
    private static final String JS_EPG =
            "(function(){"
                    + "var a=document.querySelector('#jiemu li.cur.act');"
                    + "if(a){var p=a.parentNode,c=p?p.children:[],nx='';"
                    + "for(var i=0;i<c.length;i++){if(c[i]===a&&c[i+1]){nx=c[i+1].innerText;break}}"
                    + "return (a.innerText||'')+(nx?('  '+nx):'')}"
                    + "var b=document.getElementsByClassName('tvSelectJiemu');"
                    + "if(b&&b.length>1){return (b[0].innerText||'')+'  '+(b[1].innerText||'')}"
                    + "return ''})()";

    /** 探测是否真的出画面了 */
    private static final String JS_CHECK =
            "(function(){var v=document.querySelector('video');return (v&&v.readyState>0)?1:0})()";

    private static final long PROBE_DELAY = 12_000L;
    private static final long QUALITY_DELAY = 3_000L;   // 等播放器初始化完再切清晰度
    private static final long DIGIT_TIMEOUT = 2_500L;

    /** 清晰度档位名，下标即偏好值 */
    static final String[] QUALITY_NAME = {"自动", "流畅", "标清", "高清", "超清"};

    private WebView web;
    private Handler handler = new Handler();

    private int index = 0;
    private boolean changing;
    private boolean useBackup;
    private boolean canLoadX5;
    private boolean directChange;
    private int textSize = 22;
    private int quality = 2;   // 默认标清 480P

    private StringBuilder digits = new StringBuilder();
    private String epg = "";

    private View loadingView;
    private TextView loadingText;
    private TextView infoView;
    private TextView digitView;

    private View drawer;
    private View scrollCctv;
    private View scrollLocal;
    private LinearLayout listCctv;
    private LinearLayout listLocal;
    private boolean drawerOpen;
    private int group;   // 0=央视 1=卫视
    private int row;     // 组内选中行

    private View menuBar;
    private View[] menuItems;
    private Button menuQuality;
    private int menuIndex;
    private boolean menuOpen;

    private boolean backPressedOnce;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean useX5 = prefs.getBoolean("use_x5", true);
        directChange = prefs.getBoolean("direct_change", false);
        textSize = readTextSize(prefs.getString("text_size", "1"));
        quality = readQuality(prefs.getString("quality", "2"));

        loadingView = findViewById(R.id.loadingView);
        loadingText = (TextView) findViewById(R.id.loadingText);
        infoView = (TextView) findViewById(R.id.infoView);
        digitView = (TextView) findViewById(R.id.digitView);
        drawer = findViewById(R.id.drawer);
        scrollCctv = findViewById(R.id.scrollCctv);
        scrollLocal = findViewById(R.id.scrollLocal);
        listCctv = (LinearLayout) findViewById(R.id.listCctv);
        listLocal = (LinearLayout) findViewById(R.id.listLocal);
        menuBar = findViewById(R.id.menuBar);

        if (!initCore(useX5)) {
            return; // 内核不可用，已跳引导页
        }

        buildLists();
        bindMenu();
        initWeb();

        index = getSharedPreferences("tv", MODE_PRIVATE).getInt("channel", 0);
        load(index, false);
    }

    /* ---------------- 内核与偏好 ---------------- */

    private boolean initCore(boolean useX5) {
        if (useX5) {
            QbSdk.unForceSysWebView();
            canLoadX5 = QbSdk.canLoadX5(this);
            if (!canLoadX5) {
                // 只允许回引导页重试有限次，否则会形成"主界面↔引导页"死循环
                if (TvApp.canRetryInstall(this)) {
                    Toast.makeText(this, "X5 内核未就绪，返回安装流程", Toast.LENGTH_SHORT).show();
                    startActivity(new Intent(this, BootActivity.class));
                    finish();
                    return false;
                }
                // 重试次数用尽：改用系统内核，让用户至少能看，并在设置里可重试
                QbSdk.forceSysWebView();
                canLoadX5 = false;
                Toast.makeText(this, "X5 内核未就绪，暂用系统内核（可能无法播放）\n可在设置里重试安装", Toast.LENGTH_LONG).show();
            }
        } else {
            QbSdk.forceSysWebView();
            canLoadX5 = false;
        }
        Log.i(TvApp.TAG, "内核: " + (canLoadX5 ? "X5 " + QbSdk.getTbsVersion(this) : "系统 WebView"));
        return true;
    }

    private static int readTextSize(String v) {
        if ("0".equals(v)) return 18;
        if ("2".equals(v)) return 26;
        if ("3".equals(v)) return 30;
        return 22;
    }

    private static int readQuality(String v) {
        try {
            int q = Integer.parseInt(v);
            return (q >= 0 && q <= 4) ? q : 2;
        } catch (Exception e) {
            return 2; // 默认标清
        }
    }

    /* ---------------- WebView ---------------- */

    private void initWeb() {
        web = (WebView) findViewById(R.id.web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false); // 允许自动播放
        s.setUserAgent(UA);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setLoadsImagesAutomatically(false);   // 无图加载，省带宽省 CPU
        s.setBlockNetworkImage(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.LOAD_NORMAL);
        }

        web.setFocusable(false);   // 焦点交给抽屉/菜单，不让 WebView 抢

        if (canLoadX5) {
            try {
                web.getSettingsExtension().setPicModel(IX5WebSettingsExtension.PicModel_NoPic);
            } catch (Throwable ignored) {
            }
        }

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                Log.w(TvApp.TAG, "加载失败 " + errorCode + " " + description);
                fallback();
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (!"about:blank".equals(url)) {
                    view.evaluateJavascript(JS_TRIM, null);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if ("about:blank".equals(url)) {
                    return;
                }
                view.evaluateJavascript(JS_PLAY, null);
                view.setInitialScale(initialScale());
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        changing = false;
                        loadingView.setVisibility(View.GONE);
                        fetchEpg();
                        applyQuality();
                    }
                }, 1500);
            }
        });
    }

    /** 按 1920×1080 为基准算缩放，并封顶 100%：4K 屏按 200% 渲染会卡死 Mali-400 */
    private int initialScale() {
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(dm);
        double scale = Math.min(dm.widthPixels / 1920.0, dm.heightPixels / 1080.0) * 100;
        long v = Math.round(scale);
        if (v > 100) v = 100;
        if (v < 60) v = 60;
        return (int) v;
    }

    /* ---------------- 清晰度 ---------------- */

    /** 应用当前清晰度偏好（延迟到播放器初始化后） */
    private void applyQuality() {
        handler.removeCallbacks(qualityTask);
        handler.postDelayed(qualityTask, QUALITY_DELAY);
    }

    private final Runnable qualityTask = new Runnable() {
        @Override
        public void run() {
            if (web != null) {
                web.evaluateJavascript(JS_QUALITY.replace("__Q__", String.valueOf(quality)), null);
            }
        }
    };

    /** 菜单里点一下就切到下一档，并记住选择 */
    private void nextQuality() {
        quality = (quality + 1) % QUALITY_NAME.length;
        PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putString("quality", String.valueOf(quality)).apply();
        runJs(JS_QUALITY.replace("__Q__", String.valueOf(quality)));
        menuQuality.setText(qualityLabel());
        showInfo(Channel.all()[index].name, epg);
    }

    private String qualityLabel() {
        return getString(R.string.menu_quality) + "：" + QUALITY_NAME[quality];
    }

    /* ---------------- 播放与回落 ---------------- */

    private void fetchEpg() {
        web.evaluateJavascript(JS_EPG, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                String s = clean(value);
                if (s.length() > 0) {
                    epg = s;
                }
                showInfo(Channel.all()[index].name, epg);
            }
        });
    }

    /** 主源 12 秒仍无画面 → 自动切备用源（只切一次） */
    private final Runnable probeTask = new Runnable() {
        @Override
        public void run() {
            if (!changing || web == null) {
                return;
            }
            web.evaluateJavascript(JS_CHECK, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    if ("1".equals(clean(value))) {
                        return;
                    }
                    fallback();
                }
            });
        }
    };

    private void fallback() {
        if (useBackup) {
            return;
        }
        Channel ch = Channel.all()[index];
        String backup = ch.backup;
        if (backup == null || backup.equals(ch.url(false))) {
            return;
        }
        Toast.makeText(this, "主源无画面，切换备用源", Toast.LENGTH_SHORT).show();
        load(index, true);
    }

    private void load(int i, boolean backup) {
        if (i < 0 || i >= Channel.size()) {
            return;
        }
        index = i;
        useBackup = backup;
        changing = true;
        Channel ch = Channel.all()[i];

        handler.removeCallbacks(probeTask);
        handler.removeCallbacks(qualityTask);
        epg = "";
        loadingText.setText(getString(R.string.hint_loading) + "：" + ch.name);
        loadingView.setVisibility(View.VISIBLE);
        showInfo(ch.name, "");

        getSharedPreferences("tv", MODE_PRIVATE).edit().putInt("channel", i).apply();

        web.loadUrl("about:blank");   // 先清空上一页，1GB 内存下能省出几十 MB
        web.loadUrl(ch.url(backup));
        handler.postDelayed(probeTask, PROBE_DELAY);
    }

    private void reload() {
        load(index, useBackup);
    }

    private void showInfo(String name, String program) {
        String core = canLoadX5 ? "X5 " + QbSdk.getTbsVersion(this) : "系统 WebView";
        String text = name + (program == null || program.length() == 0 ? "" : "\n" + program)
                + "\n[" + core + " · " + QUALITY_NAME[quality]
                + (useBackup ? " · 备用源" : "") + "]";
        infoView.setText(text);
        infoView.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideInfo);
        handler.postDelayed(hideInfo, 8000);
    }

    private final Runnable hideInfo = new Runnable() {
        @Override
        public void run() {
            infoView.setVisibility(View.GONE);
        }
    };

    /* ---------------- 频道列表 ---------------- */

    private void buildLists() {
        Channel[] all = Channel.all();
        fill(listCctv, all, 0, Channel.CCTV_COUNT);
        fill(listLocal, all, Channel.CCTV_COUNT, Channel.size() - Channel.CCTV_COUNT);
    }

    private void fill(LinearLayout container, Channel[] all, int start, int count) {
        for (int i = start; i < start + count; i++) {
            final int idx = i;
            Button b = new Button(this);
            b.setText(all[i].name);
            b.setTextSize(textSize);
            b.setPadding(16, 12, 16, 12);
            b.setTextColor(getResources().getColor(android.R.color.white));
            b.setBackgroundResource(R.drawable.button_selector);
            b.setSingleLine(true);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeDrawer();
                    load(idx, false);
                }
            });
            b.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        row = group == 0 ? idx : idx - Channel.CCTV_COUNT;
                    }
                }
            });
            container.addView(b);
        }
    }

    private void openDrawer(int focusIndex) {
        group = focusIndex < Channel.CCTV_COUNT ? 0 : 1;
        drawerOpen = true;
        drawer.setVisibility(View.VISIBLE);
        showGroup(focusIndex);
    }

    private void closeDrawer() {
        drawerOpen = false;
        drawer.setVisibility(View.GONE);
    }

    private void showGroup(int focusIndex) {
        int target = focusIndex;
        if (group == 0) {
            if (target >= Channel.CCTV_COUNT) target = 0;
            row = target;
        } else {
            if (target < Channel.CCTV_COUNT) target = Channel.CCTV_COUNT;
            row = target - Channel.CCTV_COUNT;
        }
        scrollCctv.setVisibility(group == 0 ? View.VISIBLE : View.GONE);
        scrollLocal.setVisibility(group == 1 ? View.VISIBLE : View.GONE);
        focusRow();
    }

    private void switchGroup() {
        group = group == 0 ? 1 : 0;
        showGroup(index);
    }

    private void moveRow(int delta) {
        LinearLayout list = group == 0 ? listCctv : listLocal;
        int count = list.getChildCount();
        if (count == 0) {
            return;
        }
        row = (row + delta + count) % count;
        focusRow();
    }

    private void focusRow() {
        LinearLayout list = group == 0 ? listCctv : listLocal;
        if (row >= 0 && row < list.getChildCount()) {
            list.getChildAt(row).requestFocus();
        }
    }

    /* ---------------- 底部菜单 ---------------- */

    private void bindMenu() {
        View refresh = findViewById(R.id.menuRefresh);
        View play = findViewById(R.id.menuPlay);
        View full = findViewById(R.id.menuFull);
        menuQuality = (Button) findViewById(R.id.menuQuality);
        View setting = findViewById(R.id.menuSetting);
        menuItems = new View[]{refresh, play, full, menuQuality, setting};
        menuQuality.setText(qualityLabel());

        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeMenu();
                reload();
            }
        });
        play.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runJs(JS_TOGGLE);
            }
        });
        full.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runJs(JS_FULL);
            }
        });
        menuQuality.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                nextQuality();
            }
        });
        setting.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeMenu();
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        View.OnFocusChangeListener fl = new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) {
                    for (int i = 0; i < menuItems.length; i++) {
                        if (menuItems[i] == v) {
                            menuIndex = i;
                        }
                    }
                }
            }
        };
        for (View item : menuItems) {
            item.setOnFocusChangeListener(fl);
        }
    }

    private void openMenu() {
        menuOpen = true;
        menuBar.setVisibility(View.VISIBLE);
        menuIndex = 0;
        menuItems[0].requestFocus();
    }

    private void closeMenu() {
        menuOpen = false;
        menuBar.setVisibility(View.GONE);
    }

    private void moveMenu(int delta) {
        menuIndex = (menuIndex + delta + menuItems.length) % menuItems.length;
        menuItems[menuIndex].requestFocus();
    }

    private void runJs(String js) {
        if (web != null) {
            web.evaluateJavascript(js, null);
        }
    }

    /* ---------------- 按键 ---------------- */

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return super.dispatchKeyEvent(event);
        }
        int code = event.getKeyCode();

        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    code == KeyEvent.KEYCODE_VOLUME_UP ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI);
            return true;
        }

        if (menuOpen) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    moveMenu(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    moveMenu(1);
                    return true;
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_B:
                    closeMenu();
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }

        if (drawerOpen) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    moveRow(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    moveRow(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    switchGroup();
                    return true;
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_B:
                    closeDrawer();
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }

        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                step(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                step(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                showInfo(Channel.all()[index].name, epg);
                openDrawer(index);
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_M:
                openMenu();
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_B:
                if (backPressedOnce) {
                    finish();
                    System.exit(0);
                    return true;
                }
                backPressedOnce = true;
                Toast.makeText(this, R.string.hint_exit, Toast.LENGTH_SHORT).show();
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        backPressedOnce = false;
                    }
                }, 2000);
                return true;
            default:
                break;
        }

        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            digits.append(code - KeyEvent.KEYCODE_0);
            handler.removeCallbacks(digitTask);
            handler.postDelayed(digitTask, DIGIT_TIMEOUT);
            digitView.setText(getString(R.string.hint_switch) + digits);
            digitView.setVisibility(View.VISIBLE);
            return true;
        }

        return super.dispatchKeyEvent(event);
    }

    private void step(int delta) {
        int next = (index + delta + Channel.size()) % Channel.size();
        if (directChange) {
            load(next, false);
        } else {
            openDrawer(next);
        }
    }

    private final Runnable digitTask = new Runnable() {
        @Override
        public void run() {
            if (digits.length() == 0) {
                return;
            }
            int n = Integer.parseInt(digits.toString());
            digits.setLength(0);
            digitView.setVisibility(View.GONE);
            if (n >= 1 && n <= Channel.size()) {
                load(n - 1, false);
            }
        }
    };

    /* ---------------- 生命周期与内存 ---------------- */

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) {
            web.resumeTimers();
        }
        // 从设置页返回时同步可能改过的偏好（清晰度、换台方式）
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        quality = readQuality(prefs.getString("quality", "2"));
        directChange = prefs.getBoolean("direct_change", false);
        if (menuQuality != null) {
            menuQuality.setText(qualityLabel());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) {
            web.pauseTimers(); // 退到后台就停掉网页定时器，省 CPU
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_MODERATE && web != null) {
            web.clearCache(false); // 只清内存缓存，保留磁盘缓存
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
        }
        super.onDestroy();
    }

    private static String clean(String v) {
        if (v == null) {
            return "";
        }
        String s = v.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        return s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\").trim();
    }
}
