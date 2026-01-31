package com.lomo.demo.ui.profile;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.Permission;
import com.hjq.permissions.XXPermissions;
import com.lm.sdk.LogicalApi;
import com.lm.sdk.mode.BleDeviceInfo;
import com.lm.sdk.utils.BLEUtils;
import com.lm.sdk.utils.UtilSharedPreference;
import com.lomo.demo.R;
import com.lomo.demo.activity.HistoryListTempActivity;
import com.lomo.demo.activity.RingFileListActivity;
import com.lomo.demo.activity.SettingsActivity;
import com.lomo.demo.activity.TestActivity;
import com.lomo.demo.adapter.DeviceAdapter;
import com.lomo.demo.adapter.OnItemClickListener;
import com.lomo.demo.application.App;
import com.lomo.demo.nfc.NfcActivity;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ProfileFragment extends Fragment {

    private DeviceAdapter adapter;
    private final Set<String> macList = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ProgressBar progressBar;
    private TextView scanStateView;
    private TextView tvUserName;
    private ImageView ivUserHead;
    private Button btnSettings;
    private SharedPreferences userInfoPrefs;
    private SharedPreferences deviceHistoryPrefs;
    
    // 连接状态相关
    private boolean isManualDisconnect = false; // 是否手动断开
    private String lastConnectedMac = ""; // 最后连接的设备MAC
    private Runnable connectionCheckRunnable; // 连接状态检查Runnable
    private static final long CONNECTION_CHECK_INTERVAL = 20000; // 20秒检查一次，进一步减少检查频率
    private static final long CONNECTION_STABLE_DELAY = 8000; // 连接稳定等待时间8秒
    private long lastConnectionTime = 0; // 最后连接成功的时间
    private int connectionCheckFailCount = 0; // 连续检查失败次数
    private static final int MAX_FAIL_COUNT = 5; // 最大连续失败次数，增加容错
    private long lastReconnectAttempt = 0; // 最后重连尝试时间
    private boolean isConnecting = false; // 是否正在连接中

    private final Runnable stopScanRunnable = this::stopScan;

    private BroadcastReceiver userInfoReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("ACTION_USER_INFO_UPDATED".equals(action)) {
                loadUserInfo();
            } else if ("ACTION_BLE_CONNECTED".equals(action)) {
                // 连接成功
                isManualDisconnect = false;
                isConnecting = false; // 标记连接完成
                lastConnectionTime = System.currentTimeMillis();
                connectionCheckFailCount = 0; // 重置失败计数
                lastReconnectAttempt = 0; // 重置重连尝试时间
                updateConnectionStatus("已连接");
                Toast.makeText(requireContext(), "设备连接成功", Toast.LENGTH_SHORT).show();
                // 延迟开始定期检查连接状态，给连接稳定时间（延长稳定期）
                handler.removeCallbacks(connectionCheckRunnable);
                // 连接成功后等待更长时间再开始检查，确保连接完全稳定
                handler.postDelayed(connectionCheckRunnable, CONNECTION_STABLE_DELAY * 2 + CONNECTION_CHECK_INTERVAL);
            } else if ("ACTION_BLE_DISCONNECTED".equals(action)) {
                // 断开连接
                boolean isManual = intent.getBooleanExtra("is_manual", false);
                if (isManual) {
                    isManualDisconnect = true;
                    connectionCheckFailCount = 0; // 重置失败计数
                } else {
                    // 非手动断开，检查是否在连接稳定期内（延长稳定期判断）
                    long timeSinceConnection = System.currentTimeMillis() - lastConnectionTime;
                    if (timeSinceConnection < CONNECTION_STABLE_DELAY * 3) {
                        // 在稳定期内断开，可能是误判，不立即更新状态
                        Log.d("ProfileFragment", "连接刚建立就断开，可能是误判，等待确认。距离连接时间: " + timeSinceConnection + "ms");
                        // 延迟一段时间后再检查，如果确实断开了再处理
                        handler.postDelayed(() -> {
                            if (!BLEUtils.isGetToken() && !isManualDisconnect) {
                                Log.d("ProfileFragment", "确认连接已断开，开始重连流程");
                                updateConnectionStatus("未连接");
                                if (!TextUtils.isEmpty(lastConnectedMac)) {
                                    attemptReconnect();
                                }
                            } else if (BLEUtils.isGetToken()) {
                                Log.d("ProfileFragment", "连接已恢复，取消断开处理");
                                updateConnectionStatus("已连接");
                            }
                        }, 3000); // 3秒后再确认
                        return;
                    }
                }
                updateConnectionStatus("未连接");
                // 如果不是手动断开，尝试重连
                if (!isManualDisconnect && !TextUtils.isEmpty(lastConnectedMac)) {
                    handler.postDelayed(() -> {
                        if (!isManualDisconnect && !BLEUtils.isGetToken()) {
                            attemptReconnect();
                        }
                    }, 3000); // 延迟3秒后尝试重连，给系统时间恢复
                }
            }
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        userInfoPrefs = requireActivity().getSharedPreferences("user_info", Context.MODE_PRIVATE);
        deviceHistoryPrefs = requireActivity().getSharedPreferences("device_history", Context.MODE_PRIVATE);
        
        // 注册广播接收器
        IntentFilter filter = new IntentFilter();
        filter.addAction("ACTION_USER_INFO_UPDATED");
        filter.addAction("ACTION_BLE_CONNECTED");
        filter.addAction("ACTION_BLE_DISCONNECTED");
        requireActivity().registerReceiver(userInfoReceiver, filter);
        
        // 初始化连接状态检查
        connectionCheckRunnable = new Runnable() {
            @Override
            public void run() {
                checkConnectionStatus();
                if (!isManualDisconnect && isAdded()) {
                    handler.postDelayed(connectionCheckRunnable, CONNECTION_CHECK_INTERVAL);
                }
            }
        };
        
        // 加载历史设备
        loadHistoryDevices();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_profile, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        RecyclerView recyclerView = view.findViewById(R.id.recycler_devices);
        progressBar = view.findViewById(R.id.progress_scan);
        scanStateView = view.findViewById(R.id.tv_scan_state);
        
        // 初始化用户信息UI
        tvUserName = view.findViewById(R.id.tv_user_name);
        ivUserHead = view.findViewById(R.id.iv_user_head);
        btnSettings = view.findViewById(R.id.btn_settings);
        
        // 加载用户信息
        loadUserInfo();
        
        // 设置按钮点击事件
        btnSettings.setOnClickListener(v -> {
            startActivity(new Intent(getContext(), SettingsActivity.class));
        });
        
        // 用户头像点击事件
        ivUserHead.setOnClickListener(v -> {
            startActivity(new Intent(getContext(), SettingsActivity.class));
        });

        adapter = new DeviceAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerView.setAdapter(adapter);
        recyclerView.setNestedScrollingEnabled(false);

        adapter.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(Object o, int position) {
                // 检查蓝牙和定位是否开启
                if (!checkBluetoothAndLocation()) {
                    return;
                }
                BleDeviceInfo deviceInfo = (BleDeviceInfo) o;
                // 保存设备信息并立即连接
                connectDevice(deviceInfo);
            }
        });

        view.findViewById(R.id.btn_device_refresh).setOnClickListener(v -> {
            // 检查蓝牙和定位是否开启
            if (checkBluetoothAndLocation()) {
                ensureScanPermission();
            }
        });
        
        // 断开连接按钮
        view.findViewById(R.id.btn_device_disconnect).setOnClickListener(v -> {
            disconnectDevice();
        });
        view.findViewById(R.id.action_nfc).setOnClickListener(v -> startActivity(new Intent(getContext(), NfcActivity.class)));
        view.findViewById(R.id.action_ring_file).setOnClickListener(v -> startActivity(new Intent(getContext(), RingFileListActivity.class)));
        view.findViewById(R.id.action_history).setOnClickListener(v -> startActivity(new Intent(getContext(), HistoryListTempActivity.class)));
        view.findViewById(R.id.action_test).setOnClickListener(v -> {
            // 检查是否有已连接的设备
            BleDeviceInfo deviceBean = App.getInstance().getDeviceBean();
            if (deviceBean == null || deviceBean.getDevice() == null) {
                Toast.makeText(requireContext(), "请先连接设备", Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(new Intent(getContext(), TestActivity.class));
        });
        view.findViewById(R.id.action_permission).setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            Uri uri = Uri.fromParts("package", requireContext().getPackageName(), null);
            intent.setData(uri);
            startActivity(intent);
        });

        ensureScanPermission();
        
        // 启动连接状态检查
        handler.postDelayed(connectionCheckRunnable, CONNECTION_CHECK_INTERVAL);
        
        // 更新连接状态显示
        updateConnectionStatusDisplay();
    }
    
    /**
     * 连接设备
     */
    @SuppressLint("MissingPermission")
    private void connectDevice(BleDeviceInfo deviceInfo) {
        if (!isAdded()) {
            return;
        }
        
        BluetoothDevice device = deviceInfo.getDevice();
        if (device == null) {
            Toast.makeText(requireContext(), "设备信息无效", Toast.LENGTH_SHORT).show();
            return;
        }
        
        String mac = device.getAddress();
        isManualDisconnect = false;
        lastConnectedMac = mac;
        
        // 保存设备地址
        UtilSharedPreference.saveString(requireContext(), "address", mac);
        App.getInstance().setDeviceBean(deviceInfo);
        
        // 保存到历史设备
        saveDeviceToHistory(device);
        
        // 更新状态
        updateConnectionStatus("正在连接...");
        isConnecting = true; // 标记正在连接
        
        try {
            BLEUtils.isHIDDevice = false;
            // 确保自动重连标志开启
            App.needAutoConnect = true;
            isManualDisconnect = false; // 重置手动断开标志
            connectionCheckFailCount = 0; // 重置失败计数
            lastConnectionTime = 0; // 重置连接时间
            
            // 停止之前的连接检查
            handler.removeCallbacks(connectionCheckRunnable);
            
            // 如果已经连接到同一个设备，不需要重新连接
            if (BLEUtils.isGetToken()) {
                BleDeviceInfo currentDevice = App.getInstance().getDeviceBean();
                if (currentDevice != null && currentDevice.getDevice() != null) {
                    String currentMac = currentDevice.getDevice().getAddress();
                    if (mac.equals(currentMac)) {
                        // 已经连接到同一个设备，更新状态即可
                        updateConnectionStatus("已连接");
                        lastConnectionTime = System.currentTimeMillis();
                        isConnecting = false;
                        Toast.makeText(requireContext(), "设备已连接", Toast.LENGTH_SHORT).show();
                        return;
                    }
                }
                // 连接到不同设备，先断开旧连接
                try {
                    Log.d("ProfileFragment", "断开旧连接，准备连接新设备");
                    BLEUtils.disconnectBLE(requireActivity());
                    BLEUtils.setGetToken(false);
                    // 等待断开完成后再连接
                    handler.postDelayed(() -> {
                        if (isAdded()) {
                            BLEUtils.connectLockByBLE(requireActivity(), device);
                        }
                    }, 1000); // 增加等待时间到1秒
                } catch (Exception e) {
                    Log.e("ProfileFragment", "断开旧连接失败: " + e.getMessage());
                    // 即使断开失败，也尝试连接新设备
                    BLEUtils.connectLockByBLE(requireActivity(), device);
                }
            } else {
                // 直接连接
                BLEUtils.connectLockByBLE(requireActivity(), device);
            }
        } catch (Exception e) {
            Log.e("ProfileFragment", "连接设备失败: " + e.getMessage());
            Toast.makeText(requireContext(), "连接失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            updateConnectionStatus("连接失败");
        }
    }
    
    /**
     * 保存设备到历史记录
     */
    private void saveDeviceToHistory(BluetoothDevice device) {
        if (device == null) {
            return;
        }
        
        String mac = device.getAddress();
        String name = device.getName();
        if (TextUtils.isEmpty(name)) {
            name = "未知设备";
        }
        
        // 保存设备信息
        SharedPreferences.Editor editor = deviceHistoryPrefs.edit();
        editor.putString("last_device_mac", mac);
        editor.putString("last_device_name", name);
        editor.putLong("last_connect_time", System.currentTimeMillis());
        
        // 保存到历史列表（最多保存10个）
        String historyJson = deviceHistoryPrefs.getString("device_history_list", "[]");
        // 这里简化处理，实际可以使用JSON来存储多个设备
        editor.apply();
    }
    
    /**
     * 加载历史设备
     */
    private void loadHistoryDevices() {
        String lastMac = deviceHistoryPrefs.getString("last_device_mac", "");
        if (!TextUtils.isEmpty(lastMac)) {
            lastConnectedMac = lastMac;
        }
    }
    
    /**
     * 检查连接状态
     */
    private void checkConnectionStatus() {
        if (!isAdded()) {
            return;
        }
        
        // 如果正在连接中，不检查
        if (isConnecting) {
            Log.d("ProfileFragment", "正在连接中，跳过状态检查");
            handler.postDelayed(connectionCheckRunnable, 2000); // 2秒后再检查
            return;
        }
        
        // 如果刚连接成功，给连接稳定时间（延长稳定期）
        long timeSinceConnection = System.currentTimeMillis() - lastConnectionTime;
        if (timeSinceConnection < CONNECTION_STABLE_DELAY * 2 && lastConnectionTime > 0) {
            // 连接刚建立，不检查，避免误判
            // 继续安排下次检查
            if (!isManualDisconnect) {
                handler.postDelayed(connectionCheckRunnable, CONNECTION_CHECK_INTERVAL);
            }
            return;
        }
        
        boolean isConnected = BLEUtils.isGetToken();
        if (isConnected) {
            connectionCheckFailCount = 0; // 重置失败计数
            updateConnectionStatus("已连接");
            // 继续检查
            if (!isManualDisconnect) {
                handler.postDelayed(connectionCheckRunnable, CONNECTION_CHECK_INTERVAL);
            }
        } else {
            if (!isManualDisconnect) {
                connectionCheckFailCount++;
                // 只有连续失败多次才认为真正断开
                if (connectionCheckFailCount >= MAX_FAIL_COUNT) {
                    updateConnectionStatus("未连接");
                    connectionCheckFailCount = 0; // 重置计数
                    // 尝试重连
                    if (!TextUtils.isEmpty(lastConnectedMac)) {
                        handler.postDelayed(() -> {
                            if (!isManualDisconnect && !BLEUtils.isGetToken() && !isConnecting) {
                                attemptReconnect();
                            }
                        }, 2000);
                    }
                } else {
                    // 失败次数未达到阈值，继续检查
                    handler.postDelayed(connectionCheckRunnable, 2000); // 失败时缩短检查间隔
                }
            }
        }
    }
    
    /**
     * 更新连接状态显示
     */
    private void updateConnectionStatus(String status) {
        if (!isAdded() || scanStateView == null) {
            return;
        }
        
        requireActivity().runOnUiThread(() -> {
            String currentText = scanStateView.getText().toString();
            // 如果当前不是搜索状态，更新连接状态
            if (!currentText.contains("搜索") && !currentText.contains("扫描")) {
                scanStateView.setText("设备状态: " + status);
            }
        });
    }
    
    /**
     * 更新连接状态显示（完整信息）
     */
    private void updateConnectionStatusDisplay() {
        if (!isAdded() || scanStateView == null) {
            return;
        }
        
        boolean isConnected = BLEUtils.isGetToken();
        String status;
        if (isConnected) {
            BleDeviceInfo deviceBean = App.getInstance().getDeviceBean();
            if (deviceBean != null && deviceBean.getDevice() != null) {
                String deviceName = deviceBean.getDevice().getName();
                if (TextUtils.isEmpty(deviceName)) {
                    deviceName = deviceBean.getDevice().getAddress();
                }
                status = "已连接: " + deviceName;
            } else {
                status = "已连接";
            }
        } else {
            if (!TextUtils.isEmpty(lastConnectedMac)) {
                status = "未连接 (最后设备: " + lastConnectedMac + ")";
            } else {
                status = "未连接";
            }
        }
        
        requireActivity().runOnUiThread(() -> {
            String currentText = scanStateView.getText().toString();
            if (!currentText.contains("搜索") && !currentText.contains("扫描")) {
                scanStateView.setText("设备状态: " + status);
            }
        });
    }
    
    /**
     * 手动断开连接
     */
    private void disconnectDevice() {
        if (!BLEUtils.isGetToken()) {
            Toast.makeText(requireContext(), "当前未连接设备", Toast.LENGTH_SHORT).show();
            return;
        }
        
        isManualDisconnect = true;
        handler.removeCallbacks(connectionCheckRunnable);
        // 关闭自动重连
        App.needAutoConnect = false;
        App.isConnectionActive = false;
        
        try {
            BLEUtils.disconnectBLE(requireActivity());
            BLEUtils.setGetToken(false);
            updateConnectionStatus("已断开");
            Toast.makeText(requireContext(), "已断开连接", Toast.LENGTH_SHORT).show();
            
            // 发送断开连接广播
            Intent intent = new Intent("ACTION_BLE_DISCONNECTED");
            intent.putExtra("is_manual", true);
            requireActivity().sendBroadcast(intent);
        } catch (Exception e) {
            Log.e("ProfileFragment", "断开连接失败: " + e.getMessage());
            Toast.makeText(requireContext(), "断开连接失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 尝试重连
     */
    @SuppressLint("MissingPermission")
    private void attemptReconnect() {
        if (!isAdded() || isManualDisconnect) {
            return;
        }
        
        // 如果已经连接，不需要重连
        if (BLEUtils.isGetToken()) {
            updateConnectionStatus("已连接");
            return;
        }
        
        if (TextUtils.isEmpty(lastConnectedMac)) {
            return;
        }
        
        // 检查蓝牙和定位
        if (!checkBluetoothAndLocation()) {
            return;
        }
        
        // 防止频繁重连
        long timeSinceLastReconnect = System.currentTimeMillis() - lastReconnectAttempt;
        if (timeSinceLastReconnect < 20000 && lastReconnectAttempt > 0) {
            // 20秒内不重复重连，给连接更多稳定时间
            Log.d("ProfileFragment", "距离上次重连尝试时间过短，暂不重连");
            return;
        }
        
        lastReconnectAttempt = System.currentTimeMillis();
        
        updateConnectionStatus("正在重连...");
        isConnecting = true; // 标记正在重连
        
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                return;
            }
            
            BluetoothDevice device = adapter.getRemoteDevice(lastConnectedMac);
            if (device == null) {
                return;
            }
            
            // 检查是否已配对
            Set<BluetoothDevice> bondedDevices = adapter.getBondedDevices();
            if (bondedDevices.contains(device)) {
                BLEUtils.isHIDDevice = false;
                App.needAutoConnect = true;
                connectionCheckFailCount = 0; // 重置失败计数
                lastConnectionTime = 0; // 重置连接时间
                
                // 如果当前有连接，先断开
                if (BLEUtils.isGetToken()) {
                    try {
                        BLEUtils.disconnectBLE(requireActivity());
                        BLEUtils.setGetToken(false);
                        handler.postDelayed(() -> {
                            if (isAdded()) {
                                BLEUtils.connectLockByBLE(requireActivity(), device);
                                App.getInstance().setDeviceBean(new BleDeviceInfo(device, -50));
                            }
                        }, 1000);
                    } catch (Exception e) {
                        Log.e("ProfileFragment", "断开旧连接失败: " + e.getMessage());
                        BLEUtils.connectLockByBLE(requireActivity(), device);
                        App.getInstance().setDeviceBean(new BleDeviceInfo(device, -50));
                    }
                } else {
                    BLEUtils.connectLockByBLE(requireActivity(), device);
                    App.getInstance().setDeviceBean(new BleDeviceInfo(device, -50));
                }
            } else {
                // 未配对，需要重新扫描
                updateConnectionStatus("设备未配对，请重新搜索");
            }
        } catch (Exception e) {
            Log.e("ProfileFragment", "重连失败: " + e.getMessage());
            updateConnectionStatus("重连失败");
        }
    }

    private void loadUserInfo() {
        // 加载用户名
        String userName = userInfoPrefs.getString("user_name", "大东");
        tvUserName.setText(userName);
        
        // 加载用户头像
        String avatarUri = userInfoPrefs.getString("user_avatar", null);
        if (avatarUri != null) {
            try {
                Glide.with(this)
                     .load(Uri.parse(avatarUri))
                     .circleCrop()
                     .error(R.drawable.shape_circle)
                     .into(ivUserHead);
            } catch (Exception e) {
                Log.e("ProfileFragment", "加载头像失败: " + e.getMessage());
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        handler.removeCallbacks(stopScanRunnable);
        // 不在onDestroyView中停止连接检查，让连接检查继续运行
        // handler.removeCallbacks(connectionCheckRunnable);
        BLEUtils.stopLeScan(requireActivity(), leScanCallback);
    }
    
    @Override
    public void onPause() {
        super.onPause();
        // 不在onPause中停止连接检查，保持连接状态监控
    }
    
    @Override
    public void onResume() {
        super.onResume();
        // 恢复时更新连接状态显示
        updateConnectionStatusDisplay();
        // 如果未手动断开，继续连接状态检查
        if (!isManualDisconnect) {
            handler.removeCallbacks(connectionCheckRunnable);
            handler.postDelayed(connectionCheckRunnable, CONNECTION_CHECK_INTERVAL);
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        // 注销广播接收器
        try {
            requireActivity().unregisterReceiver(userInfoReceiver);
        } catch (Exception e) {
            Log.e("ProfileFragment", "注销广播接收器失败: " + e.getMessage());
        }
    }

    private void ensureScanPermission() {
        if (!isAdded()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            XXPermissions.with(this)
                    .permission(Permission.ACCESS_FINE_LOCATION,
                            Permission.ACCESS_COARSE_LOCATION,
                            Permission.BLUETOOTH_SCAN,
                            Permission.BLUETOOTH_CONNECT,
                            Permission.BLUETOOTH_ADVERTISE)
                    .request(new SimplePermissionCallback());
        } else {
            XXPermissions.with(this)
                    .permission(Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.READ_EXTERNAL_STORAGE)
                    .request(new SimplePermissionCallback());
        }
    }

    private void startScan() {
        if (!isAdded()) {
            return;
        }
        macList.clear();
        adapter.clearData();
        scanStateView.setText("正在搜索附近设备...");
        progressBar.setVisibility(View.VISIBLE);
        BLEUtils.stopLeScan(requireActivity(), leScanCallback);
        BLEUtils.startLeScan(requireActivity(), leScanCallback);
        handler.removeCallbacks(stopScanRunnable);
        handler.postDelayed(stopScanRunnable, 6000);
    }

    private void stopScan() {
        if (!isAdded()) {
            return;
        }
        BLEUtils.stopLeScan(requireActivity(), leScanCallback);
        progressBar.setVisibility(View.GONE);
        // 扫描结束后更新连接状态显示
        updateConnectionStatusDisplay();
        if (adapter.getItemCount() == 0) {
            scanStateView.setText("未发现设备，点击重新扫描");
        } else {
            scanStateView.setText("点击列表连接设备");
        }
    }

    private class SimplePermissionCallback implements OnPermissionCallback {

        @Override
        public void onGranted(@NonNull List<String> permissions, boolean allGranted) {
            if (!isAdded()) {
                return;
            }
            if (!allGranted) {
                Toast.makeText(requireContext(), "部分权限未授予，无法完整体验", Toast.LENGTH_SHORT).show();
                return;
            }
            startScan();
        }

        @Override
        public void onDenied(@NonNull List<String> permissions, boolean doNotAskAgain) {
            if (!isAdded()) {
                return;
            }
            if (doNotAskAgain) {
                XXPermissions.startPermissionActivity(requireContext(), permissions);
            } else {
                Toast.makeText(requireContext(), "请授予蓝牙/定位权限以搜索设备", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * 检查蓝牙和定位是否开启
     * @return true表示都已开启，false表示未开启
     */
    @SuppressLint("MissingPermission")
    private boolean checkBluetoothAndLocation() {
        boolean bluetoothEnabled = false;
        boolean locationEnabled = false;
        
        // 检查蓝牙
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter != null) {
            bluetoothEnabled = bluetoothAdapter.isEnabled();
        }
        
        // 检查定位
        android.location.LocationManager locationManager = 
            (android.location.LocationManager) requireContext().getSystemService(Context.LOCATION_SERVICE);
        if (locationManager != null) {
            locationEnabled = locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                             locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER);
        }
        
        // 如果蓝牙或定位未开启，显示提示对话框
        if (!bluetoothEnabled || !locationEnabled) {
            StringBuilder message = new StringBuilder("为了正常连接设备，请开启以下功能：\n\n");
            if (!bluetoothEnabled) {
                message.append("• 蓝牙\n");
            }
            if (!locationEnabled) {
                message.append("• 定位服务\n");
            }
            message.append("\n点击确定前往设置页面开启。");
            
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("需要开启功能")
                .setMessage(message.toString())
                .setPositiveButton("去设置", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_SETTINGS);
                    startActivity(intent);
                })
                .setNegativeButton("取消", null)
                .show();
            return false;
        }
        
        return true;
    }

    @SuppressLint("MissingPermission")
    private final BluetoothAdapter.LeScanCallback leScanCallback = new BluetoothAdapter.LeScanCallback() {
        @Override
        public void onLeScan(BluetoothDevice device, int rssi, byte[] bytes) {
            if (device == null || TextUtils.isEmpty(device.getName())) {
                return;
            }
            BleDeviceInfo bleDeviceInfo = LogicalApi.getBleDeviceInfoWhenBleScan(device, rssi, bytes, false);
            if (bleDeviceInfo == null) {
                return;
            }
            if (macList.contains(device.getAddress())) {
                return;
            }
            macList.add(device.getAddress());
            if (isAdded()) {
                requireActivity().runOnUiThread(() -> adapter.updateData(bleDeviceInfo));
            }
        }
    };
}

