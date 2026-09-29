package com.taijie.tv;

/**
 * 频道表（41 个台，官方网页源，双源互为备份）。
 *
 * 【源的选择原则 —— 为 RK3229 弱机优化】
 *   央视频是 Vue 单页应用，A7 上首屏要执行大量 JS，明显偏慢；
 *   央视网是传统页面 + 播放器，DOM 更轻，换台更快。
 *   因此：央视全套（1-20）主源走央视网，央视频 pid 作备用；
 *         地方卫视（21-41）央视网没有，只能用央视频。
 *
 * 【验证情况】2026-09-29 逐个抓取核对：
 *   央视网 20 个页面全部返回正确频道名与节目单（cctvjilu=CCTV-9、cctvchild=CCTV-14、
 *   cctveurope/cctvamerica=CCTV-4 欧/美版、cctv5plus=CCTV-5+ 均已确认）。
 *   央视频 pid 经多份公开频道表交叉核对；其中 CCTV-16 的 pid(600098637) 抓取时未稳定返回
 *   频道名，故其主源用已验证的央视网 cctv16；河北卫视(600002493) 页面偶发会员提示，
 *   央视频为卫视唯一源，若失效请改用数字键选其他台。
 *
 * 【清晰度】央视网播放器有精确 id：resolution_item_{360|480|540|720|1080}_player；
 *          央视频用文字选项：蓝光 / 1080P / 超清 / 720P / 高清 / 540P（最低档 540P）。
 */
public final class Channel {

    private static final String YSP = "https://www.yangshipin.cn/tv/home?pid=";
    private static final String CCTV = "https://tv.cctv.com/live/";

    public final String name;
    public final String main;
    public final String backup;

    public Channel(String name, String main, String backup) {
        this.name = name;
        this.main = main;
        this.backup = backup;
    }

    /** 央视频道条数，用于换台抽屉分组 */
    public static final int CCTV_COUNT = 20;

    private static final String[][] DATA = {
            // 频道名, 主源, 备用源
            {"CCTV-1 综合", CCTV + "cctv1/", YSP + "600001859"},
            {"CCTV-2 财经", CCTV + "cctv2/", YSP + "600001800"},
            {"CCTV-3 综艺", CCTV + "cctv3/", null},
            {"CCTV-4 中文国际(亚)", CCTV + "cctv4/", YSP + "600001814"},
            {"CCTV-5 体育", CCTV + "cctv5/", YSP + "600001818"},
            {"CCTV-6 电影", CCTV + "cctv6/", null},
            {"CCTV-7 国防军事", CCTV + "cctv7/", YSP + "600004092"},
            {"CCTV-8 电视剧", CCTV + "cctv8/", null},
            {"CCTV-9 纪录", CCTV + "cctvjilu", YSP + "600004078"},
            {"CCTV-10 科教", CCTV + "cctv10/", YSP + "600001805"},
            {"CCTV-11 戏曲", CCTV + "cctv11/", YSP + "600001806"},
            {"CCTV-12 社会与法", CCTV + "cctv12/", YSP + "600001807"},
            {"CCTV-13 新闻", CCTV + "cctv13/", YSP + "600001811"},
            {"CCTV-14 少儿", CCTV + "cctvchild", YSP + "600001809"},
            {"CCTV-15 音乐", CCTV + "cctv15/", YSP + "600001815"},
            {"CCTV-16 奥林匹克", CCTV + "cctv16/", YSP + "600098637"},
            {"CCTV-17 农业农村", CCTV + "cctv17/", YSP + "600001810"},
            {"CCTV-5+ 体育赛事", CCTV + "cctv5plus/", YSP + "600001817"},
            {"CCTV-4 中文国际(欧)", CCTV + "cctveurope", null},
            {"CCTV-4 中文国际(美)", CCTV + "cctvamerica/", null},

            {"北京卫视", YSP + "600002309", null},
            {"江苏卫视", YSP + "600002521", null},
            {"东方卫视", YSP + "600002483", null},
            {"浙江卫视", YSP + "600002520", null},
            {"湖南卫视", YSP + "600002475", null},
            {"湖北卫视", YSP + "600002508", null},
            {"广东卫视", YSP + "600002485", null},
            {"广西卫视", YSP + "600002509", null},
            {"黑龙江卫视", YSP + "600002498", null},
            {"海南卫视", YSP + "600002506", null},
            {"重庆卫视", YSP + "600002531", null},
            {"深圳卫视", YSP + "600002481", null},
            {"四川卫视", YSP + "600002516", null},
            {"河南卫视", YSP + "600002525", null},
            {"福建东南卫视", YSP + "600002484", null},
            {"贵州卫视", YSP + "600002490", null},
            {"江西卫视", YSP + "600002503", null},
            {"辽宁卫视", YSP + "600002505", null},
            {"安徽卫视", YSP + "600002532", null},
            {"河北卫视", YSP + "600002493", null},
            {"山东卫视", YSP + "600002513", null},
    };

    private static final Channel[] CHANNELS = build();

    private static Channel[] build() {
        Channel[] list = new Channel[DATA.length];
        for (int i = 0; i < DATA.length; i++) {
            String[] row = DATA[i];
            list[i] = new Channel((i + 1) + " " + row[0], row[1], row[2]);
        }
        return list;
    }

    public static Channel[] all() {
        return CHANNELS;
    }

    public static int size() {
        return CHANNELS.length;
    }

    public String url(boolean useBackup) {
        if (useBackup) {
            return backup != null ? backup : main;
        }
        return main != null ? main : backup;
    }
}
