package com.lomo.demo.application;

import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;

import com.lm.sdk.LmAPI;
import com.lm.sdk.mode.BleDeviceInfo;
import com.lm.sdk.utils.BLEUtils;
import com.lomo.demo.adapter.DeviceBean;
import androidx.multidex.MultiDex;

/**
 * @author Lizhao
 */
public class App extends Application {
    private static App app;
    private BleDeviceInfo deviceBean;
    private BluetoothAdapter mBluetoothAdapter;
    public static boolean needAutoConnect=true;//是否需要自动重连，默认true，如果测试断连情况，可以不需要重连
    public static boolean isConnectionActive = false; // 连接是否处于活动状态
    @Override
    public void onCreate() {
        super.onCreate();
        app = this;
        LmAPI.init(this);
        LmAPI.setDebug(true);
        BLEUtils.contentTitle="ChipletRing Demo";
    }


    public static App getInstance() {
        return app;
    }
    public void setDeviceBean(BleDeviceInfo deviceBean) {
        this.deviceBean = deviceBean;

    }

    public BleDeviceInfo getDeviceBean() {
        return deviceBean;
    }


    public BluetoothAdapter getBluetoothAdapter() {
        if (mBluetoothAdapter == null) {
            BluetoothManager bluetoothManager = (BluetoothManager) getInstance().getSystemService(Context.BLUETOOTH_SERVICE);
            mBluetoothAdapter = bluetoothManager.getAdapter();
        }
        return mBluetoothAdapter;
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(this);
    }
}
