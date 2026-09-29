package com.taijie.tv;

import android.content.Context;
import android.os.Environment;
import android.text.TextUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** 只保留内核安装真正需要的几个文件操作 */
public final class FileUtil {

    /** 内核包存放目录（应用私有目录，4.4 上不需要任何存储权限） */
    public static File tbsDir(Context c) {
        File dir = c.getExternalFilesDir("TBSFile");
        if (dir == null) {
            // 没有挂载外置存储时退回内部存储，避免拼出 "null/xxx"
            dir = new File(c.getFilesDir(), "TBSFile");
        }
        return dir;
    }

    /** 内核包完整路径 */
    public static String tbsCorePath(Context c) {
        File dir = tbsDir(c);
        if (dir == null) {
            return null;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        return new File(dir, TvApp.TBS_CORE_FILE).getAbsolutePath();
    }

    /** assets 是否存在内置内核包 */
    public static boolean hasCoreInAssets(Context c) {
        try {
            String[] files = c.getAssets().list("");
            if (files == null) {
                return false;
            }
            for (String f : files) {
                if (TvApp.TBS_CORE_FILE.equals(f)) {
                    return true;
                }
            }
        } catch (IOException ignored) {
        }
        return false;
    }

    /** 把 assets 里的内核包拷到私有目录，供 TBS 安装 */
    public static boolean copyCoreFromAssets(Context c) {
        String dest = tbsCorePath(c);
        if (dest == null) {
            return false;
        }
        File out = new File(dest);
        if (out.exists() && out.length() > 20 * 1024 * 1024) {
            return true; // 已拷过
        }
        InputStream in = null;
        OutputStream os = null;
        try {
            in = c.getAssets().open(TvApp.TBS_CORE_FILE);
            os = new FileOutputStream(out);
            byte[] buf = new byte[64 * 1024];
            int len;
            while ((len = in.read(buf)) > 0) {
                os.write(buf, 0, len);
            }
            os.flush();
            return out.length() > 20 * 1024 * 1024;
        } catch (Exception e) {
            if (out.exists()) {
                out.delete();
            }
            return false;
        } finally {
            close(in);
            close(os);
        }
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** 下载目录（备用，当前版本默认不联网拉内核） */
    public static File downloadDir(Context c) {
        return c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
    }

    public static boolean isEmpty(String s) {
        return s == null || TextUtils.isEmpty(s) || "null".equals(s) || "\"\"".equals(s);
    }
}
