package com.lomo.demo.activity;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.collection.SparseArrayCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.lomo.demo.R;
import com.lomo.demo.base.BaseActivity;
import com.lomo.demo.ui.circle.CircleFragment;
import com.lomo.demo.ui.health.HealthFragment;
import com.lomo.demo.ui.home.HomeFragment;
import com.lomo.demo.ui.profile.ProfileFragment;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends BaseActivity {

    private final SparseArrayCompat<Fragment> fragmentCache = new SparseArrayCompat<>();
    private BottomNavigationView bottomNavigationView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        MaterialToolbar toolbar = findViewById(R.id.top_app_bar);
        toolbar.setSubtitle(new SimpleDateFormat("yyyy年MM月dd日", Locale.CHINA).format(new Date()));
        toolbar.setNavigationOnClickListener(v -> bottomNavigationView.setSelectedItemId(R.id.menu_profile));

        bottomNavigationView = findViewById(R.id.bottom_navigation);
        bottomNavigationView.setOnItemSelectedListener(item -> {
            selectTab(item.getItemId());
            return true;
        });

        if (savedInstanceState == null) {
            bottomNavigationView.setSelectedItemId(R.id.menu_home);
        }

        requestNotificationPermission();
    }

    private void selectTab(@IdRes int menuId) {
        Fragment fragment = fragmentCache.get(menuId);
        if (fragment == null) {
            fragment = createFragment(menuId);
            fragmentCache.put(menuId, fragment);
        }
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commitAllowingStateLoss();
    }

    private Fragment createFragment(@IdRes int menuId) {
        if (menuId == R.id.menu_health) {
            return new HealthFragment();
        } else if (menuId == R.id.menu_profile) {
            return new ProfileFragment();
        }
        return new HomeFragment();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        1001);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        // 不在onPause中断开连接，保持连接状态
    }
    
    @Override
    protected void onStop() {
        super.onStop();
        // 不在onStop中断开连接，保持连接状态
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 只有在MainActivity销毁时才考虑断开连接
        // 但通常MainActivity是主Activity，不会轻易销毁
        // 如果需要保持连接，可以在这里也不断开
    }
}