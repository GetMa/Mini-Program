package com.lomo.demo.ui.health;

import android.annotation.SuppressLint;
import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.lm.sdk.BLEService;
import com.lm.sdk.LmAPI;
import com.lm.sdk.inter.IHeartListener;
import com.lm.sdk.inter.IQ2Listener;
import com.lm.sdk.inter.ITempListener;
import com.lm.sdk.mode.BleDeviceInfo;
import com.lm.sdk.utils.BLEUtils;
import com.lm.sdk.utils.UtilSharedPreference;
import com.lomo.demo.R;
import com.lomo.demo.activity.CollectionActivity;
import com.lomo.demo.activity.GoMoreSleepActivity;
import com.lomo.demo.activity.HistoryListTempActivity;
import com.lomo.demo.activity.TestActivity;
import com.lomo.demo.application.App;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

public class HealthFragment extends Fragment {

    private TextView tvHeartRate;
    private TextView tvBloodOxygen;
    private TextView tvStress;
    private TextView tvBodyBattery;
    private TextView tvLastUpdateTime;
    private SharedPreferences healthDataPrefs;
    private Handler handler;
    private String pendingMeasureType;
    private int connectionCheckCount = 0;
    private ProgressDialog measurementProgressDialog; // 测量进度对话框

    // 广播接收器，用于监听健康数据的更新
    private BroadcastReceiver healthDataReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("ACTION_HEALTH_DATA_UPDATED".equals(intent.getAction())) {
                // 接收到健康数据更新的广播，刷新显示
                updateHealthDataDisplay();
            }
        }
    };

    // 连接状态广播接收器
    private BroadcastReceiver connectionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("ACTION_BLE_CONNECTED".equals(action)) {
                // 连接成功后执行待处理的测量
                if (!TextUtils.isEmpty(pendingMeasureType)) {
                    handler.postDelayed(() -> {
                        performMeasurement(pendingMeasureType);
                        pendingMeasureType = null;
                    }, 500);
                }
            }
        }
    };

    // 连接状态检查Runnable
    private Runnable checkConnectionRunnable = new Runnable() {
        @Override
        public void run() {
            if (!TextUtils.isEmpty(pendingMeasureType)) {
                if (checkDeviceConnected()) {
                    // 已连接，执行测量
                    performMeasurement(pendingMeasureType);
                    pendingMeasureType = null;
                } else {
                    // 未连接，继续等待（最多等待10秒）
                    connectionCheckCount++;
                    if (connectionCheckCount < 20) { // 20次 * 500ms = 10秒
                        handler.postDelayed(checkConnectionRunnable, 500);
                    } else {
                        // 超时，取消测量
                        Toast.makeText(requireContext(), "设备连接超时，请重试", Toast.LENGTH_SHORT).show();
                        pendingMeasureType = null;
                        connectionCheckCount = 0;
                    }
                }
            }
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        healthDataPrefs = requireActivity().getSharedPreferences("health_data", Context.MODE_PRIVATE);
        handler = new Handler(Looper.getMainLooper());

        // 注册广播接收器
        IntentFilter filter = new IntentFilter();
        filter.addAction("ACTION_HEALTH_DATA_UPDATED");
        requireActivity().registerReceiver(healthDataReceiver, filter);

        // 注册连接状态广播接收器
        IntentFilter connectionFilter = new IntentFilter();
        connectionFilter.addAction("ACTION_BLE_CONNECTED");
        requireActivity().registerReceiver(connectionReceiver, connectionFilter);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_health, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // 初始化视图
        initViews(view);
        
        // 加载并显示健康数据
        updateHealthDataDisplay();
        
        // 设置点击事件
        view.findViewById(R.id.card_heart_rate).setOnClickListener(v -> startMeasurement("heart_rate"));
        view.findViewById(R.id.card_blood_oxygen).setOnClickListener(v -> startMeasurement("oxygen"));
        view.findViewById(R.id.card_stress).setOnClickListener(v -> {
            // 压力测量可以基于心率变异性等，这里暂时跳转到测试页面
            startActivity(new Intent(getContext(), TestActivity.class));
        });
        view.findViewById(R.id.card_body_battery).setOnClickListener(v -> {
            // 身体电量是计算值，点击时重新计算并更新
            calculateAndUpdateBodyBattery();
        });
        view.findViewById(R.id.card_sleep_analysis).setOnClickListener(v -> startActivity(new Intent(getContext(), GoMoreSleepActivity.class)));
        view.findViewById(R.id.card_history_summary).setOnClickListener(v -> startActivity(new Intent(getContext(), HistoryListTempActivity.class)));
        view.findViewById(R.id.card_collection).setOnClickListener(v -> startActivity(new Intent(getContext(), CollectionActivity.class)));
    }

    private void initViews(View view) {
        tvHeartRate = view.findViewById(R.id.tv_heart_rate_value);
        tvBloodOxygen = view.findViewById(R.id.tv_blood_oxygen_value);
        tvStress = view.findViewById(R.id.tv_stress_value);
        tvBodyBattery = view.findViewById(R.id.tv_body_battery_value);
        tvLastUpdateTime = view.findViewById(R.id.tv_last_update_time);
    }

    private void updateHealthDataDisplay() {
        if (!isAdded()) {
            return;
        }

        // 获取并显示心率数据
        String heartRate = healthDataPrefs.getString("heart_rate", "-");
        if (tvHeartRate != null) {
            tvHeartRate.setText("-".equals(heartRate) ? "-- 次/分" : heartRate + " 次/分");
        }

        // 获取并显示血氧数据
        String oxygen = healthDataPrefs.getString("oxygen", "-");
        if (tvBloodOxygen != null) {
            tvBloodOxygen.setText("-".equals(oxygen) ? "-- %" : oxygen + " %");
        }

        // 压力状态（可以根据心率变异性等计算，这里暂时显示正常）
        if (tvStress != null) {
            tvStress.setText("正常");
        }

        // 计算并显示身体电量
        calculateAndUpdateBodyBattery();

        // 更新最后更新时间
        if (tvLastUpdateTime != null) {
            String lastUpdate = getLastUpdateTime();
            tvLastUpdateTime.setText("最近更新 " + lastUpdate);
        }
    }

    /**
     * 计算并更新身体电量
     */
    private void calculateAndUpdateBodyBattery() {
        int bodyBattery = calculateBodyBattery();
        if (tvBodyBattery != null) {
            tvBodyBattery.setText(bodyBattery + " %");
        }
        
        // 保存身体电量数据
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        String currentTime = sdf.format(new Date());
        SharedPreferences.Editor editor = healthDataPrefs.edit();
        editor.putInt("body_battery", bodyBattery);
        editor.putString("body_battery_time", currentTime);
        editor.apply();
    }

    /**
     * 计算身体电量
     * 基于心率、血氧、体温等指标综合计算
     */
    private int calculateBodyBattery() {
        try {
            // 获取各项指标
            String heartRateStr = healthDataPrefs.getString("heart_rate", "-");
            String oxygenStr = healthDataPrefs.getString("oxygen", "-");
            String temperatureStr = healthDataPrefs.getString("temperature", "-");

            // 如果没有任何数据，返回默认值
            if ("-".equals(heartRateStr) && "-".equals(oxygenStr) && "-".equals(temperatureStr)) {
                return 50; // 默认中等电量
            }

            int score = 0;
            int count = 0;

            // 心率评分 (正常范围: 60-100 bpm，理想值: 70-80)
            if (!"-".equals(heartRateStr)) {
                try {
                    int heartRate = Integer.parseInt(heartRateStr);
                    int heartScore = 100;
                    if (heartRate < 60) {
                        // 心率过低
                        heartScore = Math.max(40, 100 - (60 - heartRate) * 2);
                    } else if (heartRate > 100) {
                        // 心率过高
                        heartScore = Math.max(40, 100 - (heartRate - 100) * 2);
                    } else if (heartRate >= 70 && heartRate <= 80) {
                        // 理想心率
                        heartScore = 100;
                    } else {
                        // 正常但非理想范围
                        heartScore = 80 - Math.abs(heartRate - 75) * 2;
                    }
                    score += heartScore;
                    count++;
                } catch (NumberFormatException e) {
                    // 忽略无效数据
                }
            }

            // 血氧评分 (正常范围: 95-100%，理想值: 98-100%)
            if (!"-".equals(oxygenStr)) {
                try {
                    int oxygen = Integer.parseInt(oxygenStr);
                    int oxygenScore = 100;
                    if (oxygen < 95) {
                        // 血氧偏低
                        oxygenScore = Math.max(30, oxygen * 2);
                    } else if (oxygen >= 98) {
                        // 理想血氧
                        oxygenScore = 100;
                    } else {
                        // 正常但非理想
                        oxygenScore = 80 + (oxygen - 95) * 4;
                    }
                    score += oxygenScore;
                    count++;
                } catch (NumberFormatException e) {
                    // 忽略无效数据
                }
            }

            // 体温评分 (正常范围: 36.0-37.2°C，理想值: 36.5-36.8°C)
            if (!"-".equals(temperatureStr)) {
                try {
                    double temperature = Double.parseDouble(temperatureStr);
                    int tempScore = 100;
                    if (temperature < 36.0) {
                        // 体温偏低
                        tempScore = Math.max(40, (int) (40 + (temperature - 35.0) * 20));
                    } else if (temperature > 37.2) {
                        // 体温偏高
                        tempScore = Math.max(40, (int) (100 - (temperature - 37.2) * 30));
                    } else if (temperature >= 36.5 && temperature <= 36.8) {
                        // 理想体温
                        tempScore = 100;
                    } else {
                        // 正常但非理想范围
                        if (temperature < 36.5) {
                            tempScore = 80 + (int) ((temperature - 36.0) * 40);
                        } else {
                            tempScore = 100 - (int) ((temperature - 36.8) * 50);
                        }
                    }
                    score += tempScore;
                    count++;
                } catch (NumberFormatException e) {
                    // 忽略无效数据
                }
            }

            // 计算平均分
            if (count > 0) {
                int bodyBattery = Math.max(0, Math.min(100, score / count));
                return bodyBattery;
            }
        } catch (Exception e) {
            Log.e("HealthFragment", "计算身体电量失败: " + e.getMessage());
        }

        return 50; // 默认值
    }

    /**
     * 获取最后更新时间
     */
    private String getLastUpdateTime() {
        String heartRateTime = healthDataPrefs.getString("heart_rate_time", "");
        String oxygenTime = healthDataPrefs.getString("oxygen_time", "");
        String temperatureTime = healthDataPrefs.getString("temperature_time", "");
        String batteryTime = healthDataPrefs.getString("battery_time", "");

        String latestTime = "";
        SimpleDateFormat inputFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        SimpleDateFormat outputFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

        try {
            Date latestDate = null;
            for (String time : new String[]{heartRateTime, oxygenTime, temperatureTime, batteryTime}) {
                if (!TextUtils.isEmpty(time) && !"未测量".equals(time)) {
                    try {
                        Date date = inputFormat.parse(time);
                        if (date != null && (latestDate == null || date.after(latestDate))) {
                            latestDate = date;
                        }
                    } catch (Exception e) {
                        // 忽略解析错误
                    }
                }
            }

            if (latestDate != null) {
                latestTime = outputFormat.format(latestDate);
            } else {
                latestTime = "暂无数据";
            }
        } catch (Exception e) {
            latestTime = "暂无数据";
        }

        return latestTime;
    }

    /**
     * 开始测量，先检查设备连接状态
     */
    private void startMeasurement(String measureType) {
        if (checkDeviceConnected()) {
            // 设备已连接，直接执行测量
            performMeasurement(measureType);
        } else {
            // 设备未连接，尝试连接设备
            pendingMeasureType = measureType;
            connectDevice();
        }
    }

    /**
     * 检查设备是否已连接
     */
    private boolean checkDeviceConnected() {
        return BLEUtils.isGetToken();
    }

    /**
     * 连接设备
     */
    @SuppressLint("MissingPermission")
    private void connectDevice() {
        if (!isAdded()) {
            return;
        }

        // 检查蓝牙和定位是否开启
        if (!checkBluetoothAndLocation()) {
            Toast.makeText(requireContext(), "请先开启蓝牙和定位服务", Toast.LENGTH_SHORT).show();
            pendingMeasureType = null;
            return;
        }

        // 获取保存的设备地址
        String mac = UtilSharedPreference.getStringValue(requireContext(), "address");
        if (TextUtils.isEmpty(mac)) {
            Toast.makeText(requireContext(), "未找到已保存的设备，请先在\"我的\"页面连接设备", Toast.LENGTH_SHORT).show();
            pendingMeasureType = null;
            return;
        }

        Toast.makeText(requireContext(), "正在连接设备...", Toast.LENGTH_SHORT).show();

        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                Toast.makeText(requireContext(), "设备不支持蓝牙", Toast.LENGTH_SHORT).show();
                pendingMeasureType = null;
                return;
            }

            BluetoothDevice device = adapter.getRemoteDevice(mac);
            if (device == null) {
                Toast.makeText(requireContext(), "设备地址无效", Toast.LENGTH_SHORT).show();
                pendingMeasureType = null;
                return;
            }

            // 检查是否已配对
            Set<BluetoothDevice> bondedDevices = adapter.getBondedDevices();
            if (bondedDevices.contains(device)) {
                // 已配对，直接连接
                BLEUtils.connectLockByBLE(requireActivity(), device);
                // 设置设备信息
                App.getInstance().setDeviceBean(new BleDeviceInfo(device, -50));
                // 开始检查连接状态
                connectionCheckCount = 0;
                handler.postDelayed(checkConnectionRunnable, 500);
            } else {
                // 未配对，需要扫描并连接
                Toast.makeText(requireContext(), "设备未配对，请先在\"我的\"页面连接设备", Toast.LENGTH_SHORT).show();
                pendingMeasureType = null;
            }
        } catch (Exception e) {
            Log.e("HealthFragment", "连接设备失败: " + e.getMessage());
            Toast.makeText(requireContext(), "连接设备失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            pendingMeasureType = null;
        }
    }

    /**
     * 检查蓝牙和定位是否开启
     */
    @SuppressLint("MissingPermission")
    private boolean checkBluetoothAndLocation() {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            return false;
        }

        android.location.LocationManager locationManager =
                (android.location.LocationManager) requireContext().getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) {
            return false;
        }

        return locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
               locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER);
    }

    /**
     * 执行测量
     */
    private void performMeasurement(String measureType) {
        if (!isAdded()) {
            return;
        }

        if (!checkDeviceConnected()) {
            Toast.makeText(requireContext(), "设备未连接，请先连接设备", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            switch (measureType) {
                case "heart_rate":
                    measureHeartRate();
                    break;
                case "oxygen":
                    measureOxygen();
                    break;
            }
        } catch (Exception e) {
            Log.e("HealthFragment", "测量失败: " + e.getMessage());
            Toast.makeText(requireContext(), "测量失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 显示测量进度对话框
     */
    private void showMeasurementProgress(String title) {
        if (!isAdded()) {
            return;
        }
        handler.post(() -> {
            if (measurementProgressDialog != null && measurementProgressDialog.isShowing()) {
                measurementProgressDialog.dismiss();
            }
            measurementProgressDialog = new ProgressDialog(requireContext());
            measurementProgressDialog.setTitle(title);
            measurementProgressDialog.setMessage("测量中，请保持手指稳定...\n进度: 0%");
            measurementProgressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            measurementProgressDialog.setMax(100);
            measurementProgressDialog.setProgress(0);
            measurementProgressDialog.setCancelable(false);
            measurementProgressDialog.setCanceledOnTouchOutside(false);
            measurementProgressDialog.show();
        });
    }
    
    /**
     * 更新测量进度
     */
    private void updateMeasurementProgress(int progress) {
        if (!isAdded() || measurementProgressDialog == null || !measurementProgressDialog.isShowing()) {
            return;
        }
        handler.post(() -> {
            measurementProgressDialog.setProgress(progress);
            measurementProgressDialog.setMessage("测量中，请保持手指稳定...\n进度: " + progress + "%");
        });
    }
    
    /**
     * 关闭测量进度对话框
     */
    private void dismissMeasurementProgress() {
        if (measurementProgressDialog != null && measurementProgressDialog.isShowing()) {
            handler.post(() -> {
                measurementProgressDialog.dismiss();
                measurementProgressDialog = null;
            });
        }
    }

    /**
     * 测量心率
     */
    private void measureHeartRate() {
        showMeasurementProgress("测量心率");
        LmAPI.GET_HEART_ROTA((byte) 0x01, (byte) 0x30, new IHeartListener() {
            @Override
            public void progress(int progress) {
                // 测量进度
                updateMeasurementProgress(progress);
            }

            @Override
            public void resultData(int heart, int heartRota, int yaLi, int temp) {
                handler.post(() -> {
                    dismissMeasurementProgress();
                    saveHealthData("heart_rate", String.valueOf(heart));
                    Toast.makeText(requireContext(), "心率测量完成: " + heart + " bpm", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void waveformData(byte seq, byte number, String waveData) {
                // 波形数据
            }

            @Override
            public void rriData(byte seq, byte number, String data) {
                // RRI数据
            }

            @Override
            public void error(int code) {
                handler.post(() -> {
                    dismissMeasurementProgress();
                    Toast.makeText(requireContext(), "心率测量失败，错误代码: " + code, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void success() {
                // 测量成功
            }

            @Override
            public void stop() {
                // 停止测量
                handler.post(() -> {
                    dismissMeasurementProgress();
                });
            }

            @Override
            public void resultDataSHOUSHI(int heart, int bloodOxygen) {
                // 手势数据
            }
        });
    }

    /**
     * 测量血氧
     */
    private void measureOxygen() {
        showMeasurementProgress("测量血氧");
        LmAPI.GET_HEART_Q2((byte) 0x01, new IQ2Listener() {
            @Override
            public void progress(int progress) {
                // 测量进度
                updateMeasurementProgress(progress);
            }

            @Override
            public void resultData(int heart, int q2, int temp) {
                handler.post(() -> {
                    dismissMeasurementProgress();
                    saveHealthData("oxygen", String.valueOf(q2));
                    Toast.makeText(requireContext(), "血氧测量完成: " + q2 + "%", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void waveformData(byte seq, byte number, String waveData) {
                // 波形数据
            }

            @Override
            public void error(int code) {
                handler.post(() -> {
                    dismissMeasurementProgress();
                    Toast.makeText(requireContext(), "血氧测量失败，错误代码: " + code, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void success() {
                // 测量成功
            }
        });
    }

    /**
     * 保存健康数据
     */
    private void saveHealthData(String key, String value) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        String currentTime = sdf.format(new Date());

        SharedPreferences.Editor editor = healthDataPrefs.edit();
        editor.putString(key, value);
        editor.putString(key + "_time", currentTime);
        editor.apply();

        // 发送广播通知更新显示
        Intent intent = new Intent("ACTION_HEALTH_DATA_UPDATED");
        requireActivity().sendBroadcast(intent);

        // 立即更新显示
        updateHealthDataDisplay();
    }

    @Override
    public void onResume() {
        super.onResume();
        // 每次回到健康页都更新数据显示
        updateHealthDataDisplay();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // 关闭测量进度对话框
        dismissMeasurementProgress();
        // 注销广播接收器
        try {
            requireActivity().unregisterReceiver(healthDataReceiver);
            requireActivity().unregisterReceiver(connectionReceiver);
        } catch (Exception e) {
            Log.e("HealthFragment", "注销广播接收器失败: " + e.getMessage());
        }
    }
}

