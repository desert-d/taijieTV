package com.taijie.tv;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreference;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getSupportFragmentManager()
                .beginTransaction()
                .replace(android.R.id.content, new SettingsFragment())
                .commit();
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.preferences, rootKey);

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());

            Preference core = findPreference("core_info");
            if (core != null) {
                core.setSummary(TvApp.coreInfo(getContext()));
            }

            Preference retry = findPreference("retry_core");
            if (retry != null) {
                retry.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference preference) {
                        TvApp.resetInstallAttempt(getContext());
                        Toast.makeText(getContext(), "即将重启并重新安装内核", Toast.LENGTH_SHORT).show();
                        Intent it = new Intent(getContext(), BootActivity.class);
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(it);
                        if (getActivity() != null) {
                            getActivity().finish();
                        }
                        return true;
                    }
                });
            }

            Preference device = findPreference("device_info");
            if (device != null) {
                device.setSummary(Build.MODEL + " · Android " + Build.VERSION.RELEASE
                        + " · " + (TvApp.isCpu64Bit() ? "64 位" : "32 位"));
            }

            ListPreference quality = (ListPreference) findPreference("quality");
            if (quality != null) {
                int q = 2;
                try {
                    q = Integer.parseInt(prefs.getString("quality", "2"));
                } catch (Exception ignored) {
                }
                if (q < 0 || q > 4) q = 2;
                quality.setSummary("当前：" + MainActivity.QUALITY_NAME[q]
                        + "（也可在播放页按菜单键的「清晰度」按钮实时切换）");
            }

            SwitchPreference x5Pref = (SwitchPreference) findPreference("use_x5");
            if (x5Pref != null) {
                x5Pref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                    @Override
                    public boolean onPreferenceChange(Preference preference, Object newValue) {
                        Toast.makeText(getContext(), "重启应用后生效", Toast.LENGTH_SHORT).show();
                        return true;
                    }
                });
            }
        }
    }
}
