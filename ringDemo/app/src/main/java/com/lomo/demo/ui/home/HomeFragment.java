package com.lomo.demo.ui.home;

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
import com.lomo.demo.activity.GoMoreSleepActivity;
import com.lomo.demo.activity.HistoryDataActivity;
import com.lomo.demo.activity.TestActivity;
import com.lomo.demo.application.App;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

public class HomeFragment extends Fragment {

    private TextView tvHeartRate;
    private TextView tvOxygen;
    private TextView tvTemperature;
    private TextView tvBatteryLevel;
    private TextView tvHeartRateTime;
    private TextView tvOxygenTime;
    private TextView tvTemperatureTime;
    private TextView tvBatteryTime;
    private SharedPreferences healthDataPrefs;
    private Handler handler;
    private String pendingMeasureType; // 等待连接成功后执行的测量类型
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
    private int connectionCheckCount = 0;

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
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        TextView dateView = view.findViewById(R.id.tv_today_date);
        String today = new SimpleDateFormat("yyyy年MM月dd日 EEEE", Locale.CHINA).format(new Date());
        dateView.setText(today);

        // 初始化健康指标显示控件
        initHealthDataViews(view);

        // 加载并显示健康数据
        updateHealthDataDisplay();

        View quickCheck = view.findViewById(R.id.card_micro_check);
        View quickDoctor = view.findViewById(R.id.card_ai_doctor);
        View quickReborn = view.findViewById(R.id.card_recovery);
        View sleepCard = view.findViewById(R.id.card_sleep);
        View activityCard = view.findViewById(R.id.card_activity);
        View historyEntrance = view.findViewById(R.id.btn_history_report);

        quickCheck.setOnClickListener(v -> startActivity(new Intent(getContext(), TestActivity.class)));
        quickDoctor.setOnClickListener(v -> startActivity(new Intent(getContext(), HistoryDataActivity.class)));
        quickReborn.setOnClickListener(v -> startActivity(new Intent(getContext(), GoMoreSleepActivity.class)));
        sleepCard.setOnClickListener(v -> startActivity(new Intent(getContext(), GoMoreSleepActivity.class)));
        activityCard.setOnClickListener(v -> startActivity(new Intent(getContext(), TestActivity.class)));
        historyEntrance.setOnClickListener(v -> startActivity(new Intent(getContext(), HistoryDataActivity.class)));

        // 为健康指标卡片添加点击事件，直接启动测量
        view.findViewById(R.id.card_heart_rate).setOnClickListener(v -> startMeasurement("heart_rate"));
        view.findViewById(R.id.card_oxygen).setOnClickListener(v -> startMeasurement("oxygen"));
        view.findViewById(R.id.card_temperature).setOnClickListener(v -> startMeasurement("temperature"));
        view.findViewById(R.id.card_battery).setOnClickListener(v -> startMeasurement("battery"));
    }

    private void initHealthDataViews(View view) {
        tvHeartRate = view.findViewById(R.id.tv_heart_rate_value);
        tvOxygen = view.findViewById(R.id.tv_oxygen_value);
        tvTemperature = view.findViewById(R.id.tv_temperature_value);
        tvBatteryLevel = view.findViewById(R.id.tv_battery_value);
        tvHeartRateTime = view.findViewById(R.id.tv_heart_rate_time);
        tvOxygenTime = view.findViewById(R.id.tv_oxygen_time);
        tvTemperatureTime = view.findViewById(R.id.tv_temperature_time);
        tvBatteryTime = view.findViewById(R.id.tv_battery_time);
    }

    private void updateHealthDataDisplay() {
        // 获取并显示心率数据
        String heartRate = healthDataPrefs.getString("heart_rate", "-");
        String heartRateTime = healthDataPrefs.getString("heart_rate_time", "未测量");
        tvHeartRate.setText(heartRate + " bpm");
        tvHeartRateTime.setText("测量时间: " + heartRateTime);

        // 获取并显示血氧数据
        String oxygen = healthDataPrefs.getString("oxygen", "-");
        String oxygenTime = healthDataPrefs.getString("oxygen_time", "未测量");
        tvOxygen.setText(oxygen + "%");
        tvOxygenTime.setText("测量时间: " + oxygenTime);

        // 获取并显示体温数据
        String temperature = healthDataPrefs.getString("temperature", "-");
        String temperatureTime = healthDataPrefs.getString("temperature_time", "未测量");
        tvTemperature.setText(temperature + "°C");
        tvTemperatureTime.setText("测量时间: " + temperatureTime);

        // 获取并显示电池电量
        String battery = healthDataPrefs.getString("battery", "-");
        String batteryTime = healthDataPrefs.getString("battery_time", "未测量");
        tvBatteryLevel.setText(battery + "%");
        tvBatteryTime.setText("更新时间: " + batteryTime);
    }

    @Override
    public void onResume() {
        super.onResume();
        // 每次回到首页都更新数据显示
        updateHealthDataDisplay();
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
            Log.e("HomeFragment", "连接设备失败: " + e.getMessage());
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
                case "temperature":
                    measureTemperature();
                    break;
                case "battery":
                    measureBattery();
                    break;
            }
        } catch (Exception e) {
            Log.e("HomeFragment", "测量失败: " + e.getMessage());
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
     * 测量体温
     */
    private void measureTemperature() {
        showMeasurementProgress("测量体温");
        LmAPI.READ_TEMP(new ITempListener() {
            @Override
            public void resultData(int temp) {
                handler.post(() -> {
                    dismissMeasurementProgress();
                    double temperature = temp * 0.01;
                    saveHealthData("temperature", String.format(Locale.getDefault(), "%.1f", temperature));
                    Toast.makeText(requireContext(), "体温测量完成: " + temperature + "°C", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void testing(int num) {
                // 测量中，更新进度
                handler.post(() -> {
                    double tempValue = num * 0.01;
                    updateMeasurementProgress((int) (tempValue * 10)); // 简单估算进度
                });
            }

            @Override
            public void error(int code) {
                handler.post(() -> {
                    dismissMeasurementProgress();
                    Toast.makeText(requireContext(), "体温测量失败，错误代码: " + code, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    /**
     * 获取电池电量
     */
    private void measureBattery() {
        Toast.makeText(requireContext(), "正在获取电池电量...", Toast.LENGTH_SHORT).show();
        // 电池电量通过IResponseListener的battery回调获取
        // MainActivity继承BaseActivity，BaseActivity的battery回调会保存数据并发送广播
        try {
            String oldBattery = healthDataPrefs.getString("battery", "-");
            LmAPI.GET_BATTERY((byte) 0x00);
            // 延迟检查是否有电池数据更新（通过广播）
            handler.postDelayed(() -> {
                String newBattery = healthDataPrefs.getString("battery", "-");
                if (!"-".equals(newBattery) && !newBattery.equals(oldBattery)) {
                    Toast.makeText(requireContext(), "电池电量: " + newBattery + "%", Toast.LENGTH_SHORT).show();
                    updateHealthDataDisplay();
                } else {
                    // 如果数据没有更新，等待更长时间
                    handler.postDelayed(() -> {
                        String finalBattery = healthDataPrefs.getString("battery", "-");
                        if (!"-".equals(finalBattery)) {
                            Toast.makeText(requireContext(), "电池电量: " + finalBattery + "%", Toast.LENGTH_SHORT).show();
                            updateHealthDataDisplay();
                        } else {
                            Toast.makeText(requireContext(), "获取电池电量超时，请重试", Toast.LENGTH_SHORT).show();
                        }
                    }, 2000);
                }
            }, 1000);
        } catch (Exception e) {
            Log.e("HomeFragment", "获取电池电量失败: " + e.getMessage());
            Toast.makeText(requireContext(), "获取电池电量失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
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
    public void onDestroy() {
        super.onDestroy();
        // 关闭测量进度对话框
        dismissMeasurementProgress();
        // 注销广播接收器
        try {
            requireActivity().unregisterReceiver(healthDataReceiver);
            requireActivity().unregisterReceiver(connectionReceiver);
        } catch (Exception e) {
            Log.e("HomeFragment", "注销广播接收器失败: " + e.getMessage());
        }
    }
}

