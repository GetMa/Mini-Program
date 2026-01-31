package com.lomo.demo.activity;


import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.TextUtils;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.Permission;
import com.hjq.permissions.XXPermissions;
import com.lm.sdk.AdPcmTool;
import com.lm.sdk.BLEService;
import com.lm.sdk.DataApi;
import com.lm.sdk.LmAPI;
import com.lm.sdk.LogicalApi;
import com.lm.sdk.OtaApi;
import com.lm.sdk.inter.BluetoothConnectCallback;
import com.lm.sdk.inter.I6axisListener;
import com.lm.sdk.inter.ICreateToken;
import com.lm.sdk.inter.IHeartListener;
import com.lm.sdk.inter.IHistoryListener;
import com.lm.sdk.inter.IQ2Listener;
import com.lm.sdk.inter.IResponseListener;
import com.lm.sdk.inter.IShiMiListener;
import com.lm.sdk.inter.LmOTACallback;
import com.lm.sdk.inter.LmOtaProgressListener;
import com.lm.sdk.library.utils.PreferencesUtils;
import com.lm.sdk.library.utils.ToastUtils;
import com.lm.sdk.mode.BleDeviceInfo;
import com.lm.sdk.mode.DistanceCaloriesBean;
import com.lm.sdk.mode.HistoryDataBean;
import com.lm.sdk.mode.SleepBean;
import com.lm.sdk.mode.SystemControlBean;
import com.lm.sdk.utils.BLEUtils;
import com.lm.sdk.utils.ConvertUtils;

import android.content.SharedPreferences;
import com.lm.sdk.utils.ImageSaverUtil;
import com.lm.sdk.utils.Logger;
import com.lm.sdk.utils.StringUtils;
import com.lm.sdk.utils.UtilSharedPreference;
import com.lomo.demo.R;
import com.lomo.demo.adapter.DeviceBean;
import com.lomo.demo.application.App;
import com.lomo.demo.base.BaseActivity;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class TestActivity extends BaseActivity implements IResponseListener, View.OnClickListener {
    public String TAG = getClass().getSimpleName();
    TextView tv_result;
    Button bt_step;
    Button bt_battery;
    Button bt_version;
    Button bt_sync_time;
    Button bt_start_update;
    private BleDeviceInfo deviceBean;
    private BluetoothDevice bluetoothDevice;
    static String mac;
    String outputPath = com.lomo.demo.FileUtil.getSDPath(App.getInstance(), "保存" + ".pcm");
    private List<BluetoothDevice> dataEntityList = new ArrayList<>();

    Handler handler = new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(@NonNull Message msg) {

            if (msg.what == 101) {

                String mac = UtilSharedPreference.getStringValue(TestActivity.this, "address");
                if (!TextUtils.isEmpty(mac) && !BLEUtils.isGetToken() && App.needAutoConnect) {
                    Log.e("TAG", "Handler  延迟重连  resetConnect 1111 ");
                    BLEUtils.setConnecting(false);
                   // BLEUtils.connectLockByBLE(TestActivity.this, deviceBean.getDevice());
                   connect(mac);
                }

            }
            return false;
        }
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test);
        bt_step = findViewById(R.id.bt_step);
        bt_battery = findViewById(R.id.bt_battery);
        bt_version = findViewById(R.id.bt_version);
        bt_sync_time = findViewById(R.id.bt_sync_time);
        tv_result = findViewById(R.id.tv_result);
        bt_start_update = findViewById(R.id.bt_start_update);

        bt_step.setOnClickListener(this);
        bt_battery.setOnClickListener(this);
        bt_version.setOnClickListener(this);
        bt_sync_time.setOnClickListener(this);
        bt_start_update.setOnClickListener(this);
        findViewById(R.id.bt_get_collection).setOnClickListener(this);
        findViewById(R.id.bt_clear_step).setOnClickListener(this);
        findViewById(R.id.bt_read_time).setOnClickListener(this);
        findViewById(R.id.bt_collection).setOnClickListener(this);
        findViewById(R.id.bt_blood_oxygen).setOnClickListener(this);
        findViewById(R.id.bt_heart).setOnClickListener(this);
        findViewById(R.id.bt_read_log).setOnClickListener(this);
        findViewById(R.id.bt_blood_stress).setOnClickListener(this);
        findViewById(R.id.bt_set_file).setOnClickListener(this);
        findViewById(R.id.bt_sys_control).setOnClickListener(this);
        findViewById(R.id.bt_set_BlueTooth_Name).setOnClickListener(this);
        findViewById(R.id.bt_clean_history).setOnClickListener(this);
        findViewById(R.id.bt_stop_heart).setOnClickListener(this);
        findViewById(R.id.bt_delete_data).setOnClickListener(this);
        findViewById(R.id.bt_calculate_deplete).setOnClickListener(this);
        findViewById(R.id.bt_start_audio).setOnClickListener(this);
        findViewById(R.id.bt_stop_audio).setOnClickListener(this);
        findViewById(R.id.bt_jump_page2).setOnClickListener(this);
        findViewById(R.id.bt_jump_pageFile).setOnClickListener(this);
        findViewById(R.id.tv_connect).setOnClickListener(this);
        findViewById(R.id.bt_start_play).setOnClickListener(this);
        findViewById(R.id.bt_stop_play).setOnClickListener(this);
        findViewById(R.id.bt_start_6_zhou).setOnClickListener(this);
        findViewById(R.id.bt_stop_6_zhou).setOnClickListener(this);
        findViewById(R.id.bt_jump_pageCollection).setOnClickListener(this);
        findViewById(R.id.bt_jump_goMore).setOnClickListener(this);
        findViewById(R.id.bt_jump_historyTemp).setOnClickListener(this);
        findViewById(R.id.bt_view_history).setOnClickListener(this);
        findViewById(R.id.bt_export_history).setOnClickListener(this);
        findViewById(R.id.bt_clear_local_history).setOnClickListener(this);

        File file = new File(outputPath);
        file.delete();

        //获取上个页面传递过来的deviceBean对象
        Intent intent = getIntent();
        if (intent != null) {
            deviceBean = App.getInstance().getDeviceBean();
            if (deviceBean == null || deviceBean.getDevice() == null) {
                Toast.makeText(this, "设备信息无效，请重新选择!", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            bluetoothDevice = deviceBean.getDevice();
            mac = bluetoothDevice.getAddress();
            if (mac == null || mac.isEmpty()) {
                Toast.makeText(this, "设备地址无效，请重新选择!", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            try {
                BLEUtils.removeBond(deviceBean.getDevice());
                BLEUtils.connectLockByBLE(this, bluetoothDevice);
            } catch (Exception e) {
                Toast.makeText(this, "连接设备失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                finish();
            }
        } else {
            Toast.makeText(this, "未知设备，请重新选择!", Toast.LENGTH_SHORT).show();
            finish();
        }

        LogicalApi.createToken("76d07e37bfe341b1a25c76c0e25f457a", "1204491582@qq.com", new ICreateToken() {
            @Override
            public void getTokenSuccess() {

            }

            @Override
            public void error(String msg) {

            }
        });
    }


    /**
     * 断联以后，重连
     *
     * @param mac
     */
    private void connect(String mac) {
        dataEntityList.clear();
        Logger.show(TAG, "connect=" + mac, 6);
        this.mac = mac;
        //合并
        checkPermission();
    }

    public void checkPermission() {
        // 先检查蓝牙和定位是否开启
        if (!checkBluetoothAndLocation()) {
            return;
        }

        String[] permission;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permission = new String[]{Permission.ACCESS_FINE_LOCATION, Permission.BLUETOOTH_CONNECT, Permission.BLUETOOTH_SCAN};
        } else {
            permission = new String[]{Permission.READ_MEDIA_IMAGES, Permission.READ_MEDIA_VIDEO, Permission.READ_MEDIA_AUDIO, Permission.WRITE_EXTERNAL_STORAGE, Permission.ACCESS_FINE_LOCATION};
        }
        XXPermissions.with(this).permission(permission)
                .request(new OnPermissionCallback() {
                    @Override
                    public void onGranted(@NonNull List<String> permissions, boolean allGranted) {
                        if (!allGranted) {

                            return;
                        }

                        Logger.show("ConnectDevice", "mac :" + mac);
                        BluetoothDevice remote = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(mac);
                        if (BLEService.isGetToken()) {
                            Logger.show("ConnectDevice", " 蓝牙已连接");

                        } else if (remote != null && (mac).equalsIgnoreCase(remote.getAddress())) {
                            Set<BluetoothDevice> bondedDevices = BluetoothAdapter.getDefaultAdapter().getBondedDevices();
                            Logger.show("ConnectDevice", " 蓝牙 RemoteDevice 连接   ");

                            //如果系统蓝牙已经有绑定的戒指，直接连接
                            if (bondedDevices.contains(remote)) {

                                BLEUtils.stopLeScan(TestActivity.this, leScanCallback);
                                BLEUtils.connectLockByBLE(TestActivity.this, remote);
                            } else {//如果没有，就进入扫描

                                Logger.show("ConnectDevice", " 蓝牙 startLeScan 连接   ");
                                BLEUtils.stopLeScan(TestActivity.this, leScanCallback);
                                BLEUtils.startLeScan(TestActivity.this, leScanCallback);
                            }
                            App.getInstance().setDeviceBean(new BleDeviceInfo(remote, -50));
                        } else {
                            Logger.show("ConnectDevice", " 蓝牙1 startLeScan 连接   ");
                            BLEUtils.stopLeScan(TestActivity.this, leScanCallback);
                            BLEUtils.startLeScan(TestActivity.this, leScanCallback);
                        }
                    }
                });
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
            (android.location.LocationManager) getSystemService(Context.LOCATION_SERVICE);
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
            
            new AlertDialog.Builder(this)
                .setTitle("需要开启功能")
                .setMessage(message.toString())
                .setPositiveButton("去设置", (dialog, which) -> {
                    Intent intent = new Intent(android.provider.Settings.ACTION_SETTINGS);
                    startActivity(intent);
                })
                .setNegativeButton("取消", null)
                .show();
            return false;
        }
        
        return true;
    }

    @SuppressLint("MissingPermission")
    private BluetoothAdapter.LeScanCallback leScanCallback = new BluetoothAdapter.LeScanCallback() {
        @Override
        public void onLeScan(BluetoothDevice device, int rssi, byte[] bytes) {
            if (device == null || StringUtils.isEmpty(device.getName())) {
                return;
            }
            if ((mac).equalsIgnoreCase(device.getAddress()) || !BLEService.isGetToken()) {
                if (dataEntityList.contains(device)) {
                    return;
                }
                Logger.show("ConnectDevice", "(mac).equalsIgnoreCase(device.getAddress())");
                try {

                    //是否符合条件，符合条件，会返回戒指设备信息
                    BleDeviceInfo bleDeviceInfo = LogicalApi.getBleDeviceInfoWhenBleScan(device, rssi, bytes, false);
                    if (bleDeviceInfo == null) {
                        Log.i("bleDeviceInfo", "null");
                        return;
                    }


                    App.getInstance().setDeviceBean(bleDeviceInfo);
                    dataEntityList.add(device);
                    BLEUtils.stopLeScan(TestActivity.this, leScanCallback);
                    BLEUtils.connectLockByBLE(TestActivity.this, device);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 不在这里断开连接，让连接在应用级别保持
        // 只有在用户明确要求断开时才断开连接
        // BLEUtils.disconnectBLE(this);

        handler.removeMessages(101);
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
    public void lmBleConnecting(int i) {
        postView("\n连接中..." + i);
    }

    @Override
    public void lmBleConnectionSucceeded(int i) {
        if (i == 7) {
            // 延迟设置连接状态，确保连接完全建立
            handler.postDelayed(() -> {
                BLEUtils.setGetToken(true);
                App.isConnectionActive = true; // 标记连接为活动状态
                App.needAutoConnect = true; // 确保自动重连开启
                postView("\n连接成功");
                
                // 再次延迟同步时间和发送广播，确保连接完全稳定
                handler.postDelayed(() -> {
                    try {
                        LmAPI.SYNC_TIME();
                        postView("\n已自动同步戒指时间");
                    } catch (Exception e) {
                        Log.e("TestActivity", "同步时间失败: " + e.getMessage());
                    }
                    
                    // 再次延迟发送广播，确保连接完全稳定
                    handler.postDelayed(() -> {
                        // 发送连接成功广播
                        Intent intent = new Intent("ACTION_BLE_CONNECTED");
                        sendBroadcast(intent);
                    }, 2000); // 再延迟2秒确保连接稳定
                }, 1000); // 延迟1秒后同步时间
            }, 1500); // 延迟1.5秒确保连接稳定
        }

    }

    @Override
    public void lmBleConnectionFailed(int i) {
        BLEUtils.setGetToken(false);
        App.isConnectionActive = false; // 标记连接为非活动状态
        postView("\n连接失败 ");

        Log.e("ConnectDevice", " 蓝牙 connectionFailed");
        
        // 发送断开连接广播
        Intent intent = new Intent("ACTION_BLE_DISCONNECTED");
        sendBroadcast(intent);

        handler.removeMessages(101);
        handler.sendEmptyMessageDelayed(101, 5000);

    }


    @Override
    public void SystemControl(SystemControlBean systemControlBean) {
        postView("\nSystemControl：" + systemControlBean.toString());
    }

    @Override
    public void setUserInfo(byte result) {

    }

    @Override
    public void getUserInfo(int sex, int height, int weight, int age) {

    }

    @Override
    public void CONTROL_AUDIO(byte[] bytes) {
        postView("\n音频结果：" + Arrays.toString(bytes));
        byte[] adToPcm = new AdPcmTool().adpcmToPcmFromJNI(bytes);
//
//        savePcmFile(outputPath,adToPcm);
//        postView("\n已保存：" + outputPath);
    }

    public static void savePcmFile(String filePath, byte[] byteArray) {
        try (FileOutputStream fos = new FileOutputStream(filePath, true)) {
            fos.write(byteArray);
            //  fos.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void motionCalibration(byte b) {

    }

    @Override
    public void stopBloodPressure(byte b) {

    }

    @Override
    public void VERSION(byte b, String s) {
        postView("\n获取版本信息成功" + s);
    }

    @Override
    public void syncTime(byte b, byte[] timeBytes) {
        if (b == 0) {
            postView("\n" + "同步时间成功");
        } else {
            //timeBytes转成int数值


            postView("\n读取时间成功：" + ConvertUtils.BytesToLong(timeBytes));
        }

    }

    @Override
    public void stepCount(byte[] bytes) {
        postView("\n获取步数成功：" + ConvertUtils.BytesToInt(bytes));
    }

    @Override
    public void clearStepCount(byte b) {
        if (b == 0x01) {
            postView("\n清空步数成功");

        }
    }

    @Override
    public void battery(byte b, byte b1) {
        postView("\n获取电量成功：" + b1);
        // 保存电池电量数据
        saveHealthDataAndNotify("电量", String.valueOf(b1));
    }

    @Override
    public void timeOut() {

    }

    @Override
    public void saveData(String s) {
    }

    @Override
    public void reset(byte[] bytes) {
        postView("\n恢复出厂设置成功");
    }

    @Override
    public void setCollection(byte result) {
        if (result == (byte) 0x00) {
            postView("\n设置采集周期失败");
        } else if (result == (byte) 0x01) {
            postView("\n设置采集周期成功");
        }
    }

    @Override
    public void getCollection(byte[] bytes) {
        postView("\n获取采集周期成功：" + ConvertUtils.BytesToInt(bytes));
    }

    /**
     * 获取序列号，私版
     *
     * @param bytes
     */
    @Override
    public void getSerialNum(byte[] bytes) {

    }

    /**
     * 设置序列号，私版
     *
     * @param b
     */
    @Override
    public void setSerialNum(byte b) {

    }


    @Override
    public void cleanHistory(byte data) {
        if (data == (byte) 0x01) {
            postView("\n清除历史数据成功");
        }
    }

    @Override
    public void setBlueToolName(byte data) {
        if (data == (byte) 0x01) {
            postView("\n设置蓝牙名称成功");
        }
    }

    @Override
    public void readBlueToolName(byte len, String name) {
        postView("\n蓝牙名称长度：" + len + " 蓝牙名称：" + name);
    }

    @Override
    public void stopRealTimeBP(byte isSend) {

    }

    @Override
    public void BPwaveformData(byte seq, byte number, String waveDate) {
        postView("最终数据 " + waveDate + "\n");
        showMeasurementResult("血压", waveDate, "");
    }

    @Override
    public void onSport(int type, byte[] data) {
        postView("type:" + type + " data:" + data + "\n");
        Logger.show("Sport", "type:" + type + " data:" + data);
    }

    @Override
    public void breathLight(byte time) {
        postView("time:" + time);
    }

    @Override
    public void SET_HID(byte result) {
        postView("结果：" + result + "\n");
    }

    @Override
    public void GET_HID(byte touch, byte gesture, byte system) {
        postView("touch：" + touch + " gesture：" + gesture + " system：" + system + "\n");
    }

    @Override
    public void GET_HID_CODE(byte[] bytes) {
        postView("支持与否：" + bytes[0] + " 触摸功能：" + bytes[1] + " 空中手势：" + bytes[9] + "\n");
    }

    @Override
    public void GET_CONTROL_AUDIO_ADPCM(byte b) {

    }

    @Override
    public void SET_AUDIO_ADPCM_AUDIO(byte b) {

    }

    @Override
    public void setAudio(short totalLength, int index, byte[] audioData) {

    }

    @Override
    public void stopHeart(byte data) {
        if (data == (byte) 0x01) {
            postView("\n停止心率成功");
        }
    }

    @Override
    public void stopQ2(byte data) {
        if (data == (byte) 0x01) {
            postView("\n停止血氧成功");
        }
    }

    @Override
    public void GET_ECG(byte[] bytes) {

    }

    @Override
    public void appBind(SystemControlBean systemControlBean) {

    }

    @Override
    public void appConnect(SystemControlBean systemControlBean) {

    }

    @Override
    public void appRefresh(SystemControlBean systemControlBean) {

    }

    /**
     * 检查设备是否已连接
     * @return true表示已连接，false表示未连接
     */
    private boolean checkDeviceConnected() {
        if (!BLEUtils.isGetToken()) {
            postView("\n设备未连接，请先连接设备");
            Toast.makeText(this, "设备未连接，请先连接设备", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    @Override
    public void onClick(View view) {
        if (view.getId() == R.id.bt_clear_step) {
            if (!checkDeviceConnected()) return;
            postView("\n开始清空步数");
            try {
                LmAPI.CLEAR_COUNTING();
            } catch (Exception e) {
                postView("\n清空步数失败: " + e.getMessage());
            }
        }

        if (view.getId() == R.id.bt_sys_control) {
            if (!checkDeviceConnected()) return;
            try {
                BLEService.readRomoteRssi();
                postView("\nrssi == " + BLEService.RSSI);
            } catch (Exception e) {
                postView("\n读取信号强度失败: " + e.getMessage());
            }
        }

        if (view.getId() == R.id.bt_sync_time) {
            if (!checkDeviceConnected()) return;
            postView("\n开始同步时间");
            try {
                LmAPI.SYNC_TIME();
            } catch (Exception e) {
                postView("\n同步时间失败: " + e.getMessage());
            }
        }

        if (view.getId() == R.id.bt_read_time) {
            if (!checkDeviceConnected()) return;
            postView("\n开始读取时间");
            try {
                LmAPI.READ_TIME();
            } catch (Exception e) {
                postView("\n读取时间失败: " + e.getMessage());
            }

//            Intent updateIntent = new Intent("ACTION_UPDATE_TITLE");
//            updateIntent.putExtra("EXTRA_NEW_TITLE", "新的标题");
////            String sdkPackageName = "com.lm.sdk"; // 替换为你的SDK的实际包名
////            updateIntent.setPackage(sdkPackageName); // 这行代码确保了广播是显式的
//            sendBroadcast(updateIntent);
        }

        if (view.getId() == R.id.bt_set_file) {
            String filePath = "/storage/emulated/0/1/ota/BCL603M1_2.4.4.11.hex16";
            postView("\n设置文件固定路径为:" + filePath);
            OtaApi.setUpdateFile(filePath);
        }

        if (view.getId() == R.id.bt_start_update) {
            postView("\n打开注释，传入本固件的版本号，才能测试升级");
            //提供给第三方使用的ota升级，已包含检查当前版本号是否需要更新
//            OtaApi.otaUpdateWithCheckVersion("7.1.5.0Z3R", TestActivity.this, App.getInstance().getDeviceBean().getDevice(), App.getInstance().getDeviceBean().getRssi(), new LmOtaProgressListener() {
//                @Override
//                public void error(String message) {
//                    postView("\nota升级出错："+message);
//                }
//
//                @Override
//                public void onProgress(int i) {
//                    //  postView("\nota升级进度:"+i);
//                    Logger.show("OTA","OTA升级"+i);
//                }
//
//                @Override
//                public void onComplete() {
//                    postView("\nota升级结束");
//                    OtaApi.destoryOta(TestActivity.this);
//                }
//
//                @Override
//                public void isLatestVersion() {
//                    postView("\n已是最新版本");
//                }
//            });
        }

        if (view.getId() == R.id.bt_version) {
            if (!checkDeviceConnected()) return;
            postView("\n开始获取版本信息");
            try {
                LmAPI.GET_VERSION((byte) 0x00);
            } catch (Exception e) {
                postView("\n获取版本信息失败: " + e.getMessage());
            }
        }

        if (view.getId() == R.id.bt_battery) {
            if (!checkDeviceConnected()) return;
            postView("\n开始获取电量信息");
            try {
                LmAPI.GET_BATTERY((byte) 0x00);
            } catch (Exception e) {
                postView("\n获取电量信息失败: " + e.getMessage());
            }
        }

        if (view.getId() == R.id.bt_step) {
            if (!checkDeviceConnected()) return;
            postView("\n开始获取步数信息");
            try {
                LmAPI.STEP_COUNTING();
            } catch (Exception e) {
                postView("\n获取步数信息失败: " + e.getMessage());
            }
        }

        if (view.getId() == R.id.bt_collection) {
            if (!checkDeviceConnected()) return;
            postView("\n开始设置采集周期");
            try {
                LmAPI.SET_COLLECTION(1200);
            } catch (Exception e) {
                postView("\n设置采集周期失败: " + e.getMessage());
            }
        }

        if (view.getId() == R.id.bt_get_collection) {
            if (!checkDeviceConnected()) return;
            postView("\n开始获取采集周期");
            try {
                LmAPI.GET_COLLECTION();
            } catch (Exception e) {
                postView("\n获取采集周期失败: " + e.getMessage());
            }
        }
        if (view.getId() == R.id.bt_start_play) {
            if (!checkDeviceConnected()) return;
            postView("\n开始游戏");
            //postView("\n开始读取未上传数据");
            try {
                LmAPI.READ_6_AXIS_SENSORS_SHIMI(new IShiMiListener() {
                @Override
                public void startPlay6Zhou(int state, int sszx, int ssfy, int sssd, int sjzx, int sjfy, int zzjsd, int js, int xzjsd, int yzjsd) {
                    postView("\n6轴数据:state:" +state+",sszx:" +sszx+",ssfy:"+ssfy+",sssd:"+sssd+",sjzx:"+sjzx+",sjfy:"+sjfy
                            +",zzjsd:"+zzjsd+",js:"+js+",xzjsd:"+xzjsd+",yzjsd:"+yzjsd);
                }

                @Override
                public void startPlay3Zhou(int state, int zzjsd, int xzjsd, int yzjsd) {

                }

                @Override
                public void stopPlay(boolean success) {

                }

                @Override
                public void acceleration(boolean success) {

                }
            });
            } catch (Exception e) {
                postView("\n开始游戏失败: " + e.getMessage());
            }
        }
        if (view.getId() == R.id.bt_stop_play) {
            if (!checkDeviceConnected()) return;
            postView("\n停止游戏");
            //postView("\n开始读取未上传数据");
            try {
                LmAPI.STOP_PLAY_SHIMMI(new IShiMiListener() {
                @Override
                public void startPlay6Zhou(int state, int sszx, int ssfy, int sssd, int sjzx, int sjfy, int zzjsd, int js, int xzjsd, int yzjsd) {

                }

                @Override
                public void startPlay3Zhou(int state, int zzjsd, int xzjsd, int yzjsd) {

                }

                @Override
                public void stopPlay(boolean success) {

                }

                @Override
                public void acceleration(boolean success) {

                }
            });
            } catch (Exception e) {
                postView("\n停止游戏失败: " + e.getMessage());
            }
        }

        if (view.getId() == R.id.bt_start_6_zhou) {
            if (!checkDeviceConnected()) return;
            postView("\n开始6轴传感器数据");
            //postView("\n开始读取未上传数据");
            try {
                LmAPI.READ_6_AXIS_ACCELERATION(new I6axisListener() {
                @Override
                public void turnOff() {
                    postView("\n6轴关闭");
                }

                @Override
                public void sensorsData(String bpData) {
                    postView("\n6轴数据:" + bpData);
                    ImageSaverUtil.saveImageToInternalStorage(TestActivity.this,"发送6轴指令="+bpData,"LM","6轴.txt",true);
                }

                @Override
                public void deviceBusy() {
                    postView("\n设备繁忙");
                }
            });
            } catch (Exception e) {
                postView("\n开始6轴传感器失败: " + e.getMessage());
            }
        }
        if (view.getId() == R.id.bt_stop_6_zhou) {
            if (!checkDeviceConnected()) return;
            postView("\n停止6轴传感器数据");
            //postView("\n开始读取未上传数据");
            try {
                LmAPI.TURN_OFF_6_AXIS_SENSORS(new I6axisListener() {
                @Override
                public void turnOff() {
                    postView("\n6轴关闭");
                }

                @Override
                public void sensorsData(String bpData) {

                }

                @Override
                public void deviceBusy() {

                }
            });
            } catch (Exception e) {
                postView("\n停止6轴传感器失败: " + e.getMessage());
            }
        }


        if (view.getId() == R.id.bt_blood_oxygen) {
            if (!checkDeviceConnected()) return;
            postView("\n开始测量血氧");
            try {
                LmAPI.GET_HEART_Q2((byte) 0x01, new IQ2Listener() {
                @Override
                public void progress(int progress) {
                    postView("\n测量血氧进度：" + progress + "%");
                }

                @Override
                public void resultData(int heart, int q2, int temp) {
                    postView("\n测量血氧数据：" + q2);
                    showMeasurementResult("血氧", String.valueOf(q2), "%");
                }

                @Override
                public void waveformData(byte seq, byte number, String waveData) {
                    tv_result.setText(waveData);
                }

                @Override
                public void error(int code) {
                    postView("\n测量血氧错误：" + code);
                }

                @Override
                public void success() {
                    postView("\n测量血氧完成");
                }

            });
            } catch (Exception e) {
                postView("\n测量血氧失败: " + e.getMessage());
            }
        }

        if (view.getId() == R.id.bt_heart) {
            if (!checkDeviceConnected()) return;
            postView("\n开始测量心率");
            try {
                LmAPI.GET_HEART_ROTA((byte) 0x01, (byte) 0x30, new IHeartListener() {
                @Override
                public void progress(int progress) {
                    postView("\n测量心率进度：" + progress + "%");
                }

                @Override
                public void resultData(int heart, int heartRota, int yaLi, int temp) {
                        showMeasurementResult("心率", String.valueOf(heart), "次/分");
                }

                @Override
                public void waveformData(byte seq, byte number, String waveData) {
                    tv_result.setText(waveData);
                }

                @Override
                public void rriData(byte seq, byte number, String data) {
                    postView("\ndata的值是：" + data);
                }

                @Override
                public void error(int code) {
                    postView("\n测量心率错误：" + code);
                }

                @Override
                public void success() {
                    postView("\n测量心率完成");
                }

                @Override
                public void stop() {

                }

                @Override
                public void resultDataSHOUSHI(int heart, int bloodOxygen) {

                }
            });
            } catch (Exception e) {
                postView("\n测量心率失败: " + e.getMessage());
            }
        }


        if (view.getId() == R.id.bt_read_log) {
            if (!checkDeviceConnected()) return;
            postView("\n开始读取全部数据");
            //postView("\n开始读取未上传数据");
            try {
                LmAPI.READ_HISTORY((byte) 0x01, 0, new IHistoryListener() {
                @Override
                public void error(int code) {
                    postView("\n读取历史错误码:" + code );
                    String message="";
                    if(code==0){
                        message="正在测量中";
                    }
                    if(code==1){
                        message="正在上传历史记录";
                    }
                    if(code==2){
                        message="正在删除历史记录";
                    }
                    if(code==3){
                        message="文件系统损坏";
                    }
                    postView("\n读取历史错误:" + message );

                }

                @Override
                public void success() {
                    postView("\n读取记录完成");


                }

                @Override
                public void progress(double progress, HistoryDataBean historyDataBean) {
                    postView("\n读取记录进度:" + progress + "%");
                    postView("\n记录内容:" + historyDataBean.toString());
                    // 保存历史数据到本地
                    if (historyDataBean != null) {
                        HistoryDataManager.saveHistoryData(TestActivity.this, historyDataBean);
                    }
                }

                @Override
                public void noNewDataAvailable() {

                }
            });
            } catch (Exception e) {
                postView("\n读取历史数据失败: " + e.getMessage());
            }
        }

        if (view.getId() == R.id.bt_blood_stress) {
            if (!checkDeviceConnected()) return;
            postView("\n开始获取血压数据\n");
            try {
                LmAPI.GET_BPwaveData((byte) 20, (byte) 20, (byte) 20, (byte) 20);
            } catch (Exception e) {
                postView("\n获取血压数据失败: " + e.getMessage());
            }
        }

        if (view.getId() == R.id.bt_set_BlueTooth_Name) {
            if (!checkDeviceConnected()) return;
            postView("\n设置蓝牙名称");
            //No more than 12 bytes, can be Chinese, English, numbers, that is, 4 Chinese characters or 12 English
            try {
                LmAPI.Set_BlueTooth_Name("C6");
            } catch (Exception e) {
                postView("\n设置蓝牙名称失败: " + e.getMessage());
            }
        }

        if (view.getId() == R.id.bt_clean_history) {
            if (!checkDeviceConnected()) return;
            postView("\n清除历史数据");//The historical data inside the ring is cleared
            try {
                LmAPI.CLEAN_HISTORY();
            } catch (Exception e) {
                postView("\n清除历史数据失败: " + e.getMessage());
            }
        }

        if (view.getId() == R.id.bt_stop_heart) {
            if (!checkDeviceConnected()) return;
            postView("\n停止心率");
            try {
                LmAPI.STOP_HEART();
            } catch (Exception e) {
                postView("\n停止心率失败: " + e.getMessage());
            }
        }


        if (view.getId() == R.id.bt_delete_data) {
            postView("\n删除本地数据库");//Delete the local database
            DataApi.instance.deleteHistoryData();
        }

        if (view.getId() == R.id.bt_calculate_deplete) {
            postView("\n计算距离和消耗的卡路里");
            DistanceCaloriesBean distanceCaloriesBean = LogicalApi.calculateDistance(5000, 180, 70);
            postView("\n距离：" + distanceCaloriesBean.getDistance() + "  卡路里:" + distanceCaloriesBean.getKcal());
        }

        if (view.getId() == R.id.bt_start_audio) {
            if (!checkDeviceConnected()) return;
            postView("\n开始打开音频传输");
            try {
                LmAPI.SET_AUDIO((byte) 0x01);
            } catch (Exception e) {
                postView("\n打开音频传输失败: " + e.getMessage());
            }
        }

        if (view.getId() == R.id.bt_stop_audio) {
            if (!checkDeviceConnected()) return;
            postView("\n开始关闭音频传输");
            try {
                LmAPI.SET_AUDIO((byte) 0x00);
            } catch (Exception e) {
                postView("\n关闭音频传输失败: " + e.getMessage());
            }
        }
        if (view.getId() == R.id.bt_jump_page2) {
            Intent intent = new Intent();
            intent.setClass(TestActivity.this, TestActivity2.class);
            startActivity(intent);
        }
        if (view.getId() == R.id.bt_jump_pageFile) {
            Intent intent = new Intent();
            intent.setClass(TestActivity.this, RingFileListActivity.class);
            startActivity(intent);
        }
        if (view.getId() == R.id.tv_connect) {
            if (bluetoothDevice == null) {
                Toast.makeText(this, "设备信息无效，请重新选择设备", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                BLEUtils.isHIDDevice = false;
                BLEUtils.connectLockByBLE(TestActivity.this, bluetoothDevice);
            } catch (Exception e) {
                Toast.makeText(this, "连接失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }

        if (view.getId() == R.id.bt_jump_pageCollection) {
            Intent intent = new Intent();
            intent.setClass(TestActivity.this,CollectionActivity.class);
            startActivity(intent);
        }
        if (view.getId() == R.id.bt_jump_goMore) {
            Intent intent = new Intent();
            intent.setClass(TestActivity.this,GoMoreSleepActivity.class);
            startActivity(intent);
        }
        if (view.getId() == R.id.bt_jump_historyTemp) {
            Intent intent = new Intent();
            intent.setClass(TestActivity.this,HistoryListTempActivity.class);
            startActivity(intent);
        }

        if (view.getId() == R.id.bt_view_history) {
            Intent intent = new Intent();
            intent.setClass(TestActivity.this, HistoryDataActivity.class);
            startActivity(intent);
        }

        if (view.getId() == R.id.bt_export_history) {
            // 直接跳转到历史数据页面，用户可以在那里导出
            Intent intent = new Intent();
            intent.setClass(TestActivity.this, HistoryDataActivity.class);
            startActivity(intent);
        }

        if (view.getId() == R.id.bt_clear_local_history) {
            HistoryDataManager.clearHistoryData(this);
            Toast.makeText(this, "本地历史记录已清除", Toast.LENGTH_SHORT).show();
        }

    }

    public void removeBond( BluetoothDevice btDevice){
        if(btDevice==null){
            return;
        }
        Method removeBondMethod = null;
        try {
            removeBondMethod = BluetoothDevice.class.getMethod("removeBond");
            Boolean returnValue = (Boolean) removeBondMethod.invoke(btDevice);
            returnValue.booleanValue();
//            removeBondMethod = btDevice.getClass().getMethod("removeBond");
//            Boolean returnValue = (Boolean) removeBondMethod.invoke(btDevice);
//            returnValue.booleanValue();

        } catch (Exception e) {

            throw new RuntimeException(e);
        }


    }

    public static void setMessage(Context context, String message) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setMessage(message)
                .setTitle("提示")
                .setPositiveButton("确定", null); // 添加确定按钮，点击后不执行任何操作，可根据需求修改

        AlertDialog dialog = builder.create();
        dialog.show();
    }


    /**
     * @param value 打印的log
     */
    public void postView(String value) {
        // 确保文本不为null
        if (value == null) {
            value = "";
        }
        
        // 确保文本是正确编码的字符串
        String safeText = value;
        
        tv_result.setMovementMethod(ScrollingMovementMethod.getInstance());
        tv_result.setScrollbarFadingEnabled(false);//滚动条一直显示
        tv_result.append(safeText);
        
        // 滚动到底部
        int scrollAmount = tv_result.getLayout().getLineTop(tv_result.getLineCount()) - tv_result.getHeight();
        if (scrollAmount > 0) {
            tv_result.scrollTo(0, scrollAmount);
        } else {
            tv_result.scrollTo(0, 0);
        }
    }

    private void showMeasurementResult(String itemName, String value, String unit) {
        String safeItem = TextUtils.isEmpty(itemName) ? "" : itemName;
        String safeValue = TextUtils.isEmpty(value) ? "--" : value;
        String safeUnit = TextUtils.isEmpty(unit) ? "" : unit;
        postView("\n本次" + safeItem + "测量结果为：" + safeValue + safeUnit);
        
        // 保存健康数据到SharedPreferences并发送广播
        saveHealthDataAndNotify(safeItem, safeValue);
    }
    
    private void saveHealthDataAndNotify(String itemName, String value) {
        // 确保值有效
        if (TextUtils.isEmpty(value) || "--".equals(value)) {
            return;
        }
        
        // 获取当前时间
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
        String currentTime = dateFormat.format(new Date());
        
        // 使用SharedPreferences保存数据
        SharedPreferences healthPrefs = getSharedPreferences("health_data", MODE_PRIVATE);
        SharedPreferences.Editor healthEditor = healthPrefs.edit();

        // 根据项目名称保存对应的数据
        switch (itemName) {
            case "心率":
                healthEditor.putString("heart_rate", value);
                healthEditor.putString("heart_rate_time", currentTime);
                break;
            case "血氧":
                healthEditor.putString("oxygen", value);
                healthEditor.putString("oxygen_time", currentTime);
                break;
            case "体温":
                healthEditor.putString("temperature", value);
                healthEditor.putString("temperature_time", currentTime);
                break;
            case "设备电量":
            case "电量":
                healthEditor.putString("battery", value);
                healthEditor.putString("battery_time", currentTime);
                break;
            default:
                // 可以根据需要添加其他健康指标的处理
                break;
        }

        healthEditor.apply();
        
        // 发送广播通知HomeFragment更新显示
        Intent intent = new Intent("ACTION_HEALTH_DATA_UPDATED");
        sendBroadcast(intent);
    }

    @Override
    public void battery_push(byte b, byte datum) {

    }

    @Override
    public void TOUCH_AUDIO_FINISH_XUN_FEI() {

    }
}