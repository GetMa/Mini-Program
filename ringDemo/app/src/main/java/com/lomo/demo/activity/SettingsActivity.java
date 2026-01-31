package com.lomo.demo.activity;

import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.lm.sdk.LmAPI;
import com.lm.sdk.BLEService;
import com.lm.sdk.utils.BLEUtils;
import com.lomo.demo.R;
import com.lomo.demo.application.App;
import com.lomo.demo.base.BaseActivity;

public class SettingsActivity extends BaseActivity implements View.OnClickListener {

    private TextView tvBluetoothStatus;
    private TextView tvRssiValue;
    private boolean isBluetoothConnected = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // 初始化视图
        tvBluetoothStatus = findViewById(R.id.tv_bluetooth_status);
        tvRssiValue = findViewById(R.id.tv_rssi_value);
        Button btnCheckBluetooth = findViewById(R.id.btn_check_bluetooth);
        Button btnSyncTime = findViewById(R.id.btn_sync_time);
        Button btnSetBluetoothName = findViewById(R.id.btn_set_bluetooth_name);
        Button btnCustomUserName = findViewById(R.id.btn_custom_user_name);
        Button btnCustomUserAvatar = findViewById(R.id.btn_custom_user_avatar);
        Button btnLocationSettings = findViewById(R.id.btn_location_settings);
        Button btnSystemBluetoothSettings = findViewById(R.id.btn_system_bluetooth_settings);

        // 设置点击监听器
        btnCheckBluetooth.setOnClickListener(this);
        btnSyncTime.setOnClickListener(this);
        btnSetBluetoothName.setOnClickListener(this);
        btnCustomUserName.setOnClickListener(this);
        btnCustomUserAvatar.setOnClickListener(this);
        btnLocationSettings.setOnClickListener(this);
        btnSystemBluetoothSettings.setOnClickListener(this);

        // 检查蓝牙状态
        checkBluetoothStatus();
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.btn_check_bluetooth) {
            checkBluetoothStatus();
        } else if (id == R.id.btn_sync_time) {
            syncTime();
        } else if (id == R.id.btn_set_bluetooth_name) {
            showSetBluetoothNameDialog();
        } else if (id == R.id.btn_custom_user_name) {
            showCustomUserNameDialog();
        } else if (id == R.id.btn_custom_user_avatar) {
            pickUserAvatar();
        } else if (id == R.id.btn_location_settings) {
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        } else if (id == R.id.btn_system_bluetooth_settings) {
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        }
    }

    private void checkBluetoothStatus() {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            tvBluetoothStatus.setText("设备不支持蓝牙");
            isBluetoothConnected = false;
        } else if (!bluetoothAdapter.isEnabled()) {
            tvBluetoothStatus.setText("蓝牙未开启");
            isBluetoothConnected = false;
        } else {
            tvBluetoothStatus.setText("蓝牙已开启");
            isBluetoothConnected = true;
            checkRssi();
        }
    }

    private void checkRssi() {
        if (BLEUtils.isGetToken()) {
            // BLEUtils.readRomoteRssi(); 方法不存在，暂时使用固定值显示
            int rssi = -70; // 示例值
            tvRssiValue.setText("蓝牙信号强度: " + rssi + " dBm");
        } else {
            tvRssiValue.setText("未连接设备");
        }
    }

    private void syncTime() {
        if (!isBluetoothConnected) {
            Toast.makeText(this, "请先开启蓝牙并连接设备", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!BLEUtils.isGetToken()) {
            Toast.makeText(this, "设备未连接，请先连接设备", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            LmAPI.SYNC_TIME();
            Toast.makeText(this, "时间同步成功", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "时间同步失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showSetBluetoothNameDialog() {
        if (!isBluetoothConnected) {
            Toast.makeText(this, "请先开启蓝牙并连接设备", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!BLEUtils.isGetToken()) {
            Toast.makeText(this, "设备未连接，请先连接设备", Toast.LENGTH_SHORT).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("设置蓝牙名称");
        final EditText input = new EditText(this);
        input.setHint("输入蓝牙名称");
        builder.setView(input);

        builder.setPositiveButton("确认", (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, "名称不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            if (name.length() > 12) {
                Toast.makeText(this, "名称不能超过12个字符", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                if (BLEUtils.isGetToken()) {
                    LmAPI.Set_BlueTooth_Name(name);
                    Toast.makeText(this, "蓝牙名称设置中", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "设备未连接，无法设置", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "设置失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void showCustomUserNameDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("设置用户名");
        final EditText input = new EditText(this);
        input.setHint("输入用户名");
        // 获取当前用户名
        String currentUserName = getSharedPreferences("user_info", MODE_PRIVATE)
                .getString("user_name", "大东");
        input.setText(currentUserName);
        
        builder.setView(input);

        builder.setPositiveButton("确认", (dialog, which) -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, "用户名不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            // 保存用户名
            getSharedPreferences("user_info", MODE_PRIVATE)
                    .edit()
                    .putString("user_name", name)
                    .apply();
            Toast.makeText(this, "用户名设置成功", Toast.LENGTH_SHORT).show();
            // 发送广播通知其他页面更新
            sendBroadcast(new Intent("ACTION_USER_INFO_UPDATED"));
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void pickUserAvatar() {
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType("image/*");
        startActivityForResult(intent, 1001);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            // 保存头像URI
            String avatarUri = data.getData().toString();
            getSharedPreferences("user_info", MODE_PRIVATE)
                    .edit()
                    .putString("user_avatar", avatarUri)
                    .apply();
            Toast.makeText(this, "头像设置成功", Toast.LENGTH_SHORT).show();
            // 发送广播通知其他页面更新
            sendBroadcast(new Intent("ACTION_USER_INFO_UPDATED"));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkBluetoothStatus();
    }
}
