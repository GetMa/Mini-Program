// pages/usercenter/index.js
import Toast from 'tdesign-miniprogram/toast/index';
import bluetoothService from '../../utils/ble/bluetooth';
import healthDataService from '../../utils/ble/healthDataService';

Page({
  data: {
    userInfo: {
      avatarUrl: '',
      nickName: '川哥',
      userId: '',
    },
    deviceInfo: {
      isConnected: false,
      status: '未连接',
      deviceId: null,
      deviceName: null,
    },
    isScanning: false,
    deviceList: [],
    scanTimer: null,
  },

  onLoad() {
    this.loadUserInfo();
    this.initBluetooth();
    this.loadDeviceInfo();
  },

  onShow() {
    if (typeof this.getTabBar === 'function' && this.getTabBar()) {
      this.getTabBar().init();
    }
    this.updateDeviceStatus();
  },

  onUnload() {
    // 页面卸载时停止扫描
    if (this.data.isScanning) {
      this.stopScan();
    }
  },

  // 加载用户信息
  loadUserInfo() {
    try {
      const userInfo = wx.getStorageSync('user_info') || {};
      this.setData({
        userInfo: {
          avatarUrl: userInfo.avatarUrl || '',
          nickName: userInfo.user_name || userInfo.nickName || '川哥',
          userId: userInfo.userId || userInfo.user_id || '',
        },
      });
    } catch (e) {
      console.error('加载用户信息失败:', e);
    }
  },

  // 初始化蓝牙
  initBluetooth() {
    // 设置连接成功回调
    bluetoothService.setOnConnected((device) => {
      console.log('设备连接成功', device);
      healthDataService.clearSessionMeasured();
      this.saveDeviceInfo(device.deviceId, device.name);
      this.setData({
        'deviceInfo.isConnected': true,
        'deviceInfo.status': '已连接',
        'deviceInfo.deviceId': device.deviceId,
        'deviceInfo.deviceName': device.name,
      });
      Toast({
        context: this,
        selector: '#t-toast',
        message: '设备连接成功',
      });
    });

    // 设置断开连接回调
    bluetoothService.setOnDisconnected((device) => {
      console.log('设备断开连接', device);
      try {
        wx.removeStorageSync('device_info');
      } catch (e) {
        console.error('[UserCenter] 清除设备缓存失败', e);
      }
      this.setData({
        'deviceInfo.isConnected': false,
        'deviceInfo.status': '未连接',
        'deviceInfo.deviceId': null,
        'deviceInfo.deviceName': null,
      });
      Toast({
        context: this,
        selector: '#t-toast',
        message: '设备已断开连接',
      });
    });
  },

  // 加载设备信息
  loadDeviceInfo() {
    try {
      const deviceInfo = wx.getStorageSync('device_info') || {};
      if (deviceInfo.isConnected && deviceInfo.deviceId) {
        // 检查连接状态
        const state = bluetoothService.getConnectionState();
        this.setData({
          deviceInfo: {
            isConnected: state.isConnected,
            status: state.isConnected ? '已连接' : '未连接',
            deviceId: state.deviceId,
            deviceName: state.deviceName || deviceInfo.deviceName,
          },
        });
      }
    } catch (e) {
      console.error('加载设备信息失败:', e);
    }
  },

  // 更新设备状态
  updateDeviceStatus() {
    const state = bluetoothService.getConnectionState();
    this.setData({
      'deviceInfo.isConnected': state.isConnected,
      'deviceInfo.status': state.isConnected ? '已连接' : '未连接',
      'deviceInfo.deviceId': state.deviceId,
      'deviceInfo.deviceName': state.deviceName || this.data.deviceInfo.deviceName,
    });
  },

  // 保存设备信息
  saveDeviceInfo(deviceId, deviceName) {
    try {
      wx.setStorageSync('device_info', {
        isConnected: true,
        deviceId,
        deviceName,
        connectTime: new Date().toISOString(),
      });
    } catch (e) {
      console.error('保存设备信息失败:', e);
    }
  },

  // 头像点击
  onAvatarClick() {
    wx.navigateTo({
      url: '/pages/usercenter/person-info/index',
      fail: () => {
        Toast({
          context: this,
          selector: '#t-toast',
          message: '个人中心功能开发中',
        });
      },
    });
  },

  // 设置点击
  onSettingsClick() {
    Toast({
      context: this,
      selector: '#t-toast',
      message: '设置功能开发中',
    });
  },

  // 检查蓝牙和定位权限
  async checkBluetoothAndLocationPermissions() {
    console.log('[UserCenter] 开始检查蓝牙和定位权限...');
    
    // 步骤1: 初始化蓝牙适配器（必须先初始化才能检查状态）
    try {
      // 先尝试初始化蓝牙适配器
      try {
        await bluetoothService.initAdapter();
        console.log('[UserCenter] 蓝牙适配器初始化成功');
      } catch (initErr) {
        // 如果初始化失败，可能是蓝牙未开启
        console.error('[UserCenter] 蓝牙适配器初始化失败', initErr);
        if (initErr.errCode === 10001) {
          throw new Error('蓝牙未开启');
        }
        throw new Error('蓝牙初始化失败，请检查蓝牙是否开启');
      }
      
      // 初始化成功后检查状态
      const adapterState = await this.checkBluetoothAdapterState();
      console.log('[UserCenter] 蓝牙适配器状态:', adapterState);
      
      if (!adapterState.available) {
        throw new Error('蓝牙未开启');
      }
    } catch (e) {
      console.error('[UserCenter] 蓝牙检查失败', e);
      if (e.message) {
        throw e;
      }
      throw new Error('无法检查蓝牙状态');
    }

    // 步骤2: 检查定位服务状态（Android需要定位权限才能扫描蓝牙设备）
    try {
      // 先请求定位权限
      const hasLocationPermission = await this.checkLocationPermission();
      console.log('[UserCenter] 定位权限状态:', hasLocationPermission);
      
      if (!hasLocationPermission) {
        // 尝试请求授权
        try {
          await wx.authorize({
            scope: 'scope.userLocation',
          });
          console.log('[UserCenter] 定位权限授权成功');
        } catch (authErr) {
          console.error('[UserCenter] 定位权限授权失败', authErr);
          throw new Error('需要定位权限');
        }
      }

      // 检查定位服务是否开启
      const locationInfo = await this.checkLocationService();
      console.log('[UserCenter] 定位服务状态:', locationInfo);
      
      if (!locationInfo.enabled) {
        throw new Error('定位服务未开启');
      }
    } catch (e) {
      console.error('[UserCenter] 定位检查失败', e);
      if (e.message) {
        throw e;
      }
      // 如果只是无法检查，不阻止扫描
      console.log('[UserCenter] 定位检查失败，继续尝试扫描');
    }

    console.log('[UserCenter] 权限检查通过');
    return true;
  },

  // 检查定位服务是否开启
  // 仅当明确「权限被拒绝」或「定位不可用」时认为未开启；超时、网络等其它失败不阻挡扫描
  checkLocationService() {
    return new Promise((resolve) => {
      wx.getLocation({
        type: 'gcj02',
        success: () => {
          resolve({ enabled: true });
        },
        fail: (err) => {
          console.error('[UserCenter] 定位服务检查失败', err);
          const msg = (err.errMsg || '').toLowerCase();
          if (msg.includes('auth deny') || msg.includes('authorize')) {
            resolve({ enabled: false, reason: '权限被拒绝' });
          } else if (msg.includes('location unavailable')) {
            resolve({ enabled: false, reason: '定位服务不可用' });
          } else {
            // 超时、网络等无法可靠判断，不阻挡扫描
            resolve({ enabled: true });
          }
        },
      });
    });
  },

  // 检查定位权限
  checkLocationPermission() {
    return new Promise((resolve, reject) => {
      wx.getSetting({
        success: (res) => {
          // 检查定位权限
          const hasLocationPermission = res.authSetting['scope.userLocation'];
          console.log('[UserCenter] 定位权限:', hasLocationPermission);
          resolve(hasLocationPermission === true);
        },
        fail: (err) => {
          console.error('[UserCenter] 获取权限设置失败', err);
          reject(err);
        },
      });
    });
  },

  // 扫描设备
  async onScanClick() {
    if (this.data.isScanning) {
      this.stopScan();
      return;
    }

    console.log('[UserCenter] 开始扫描流程...');

    // 步骤0: 检查蓝牙和定位权限（在授权之前先检查）
    try {
      await this.checkBluetoothAndLocationPermissions();
      console.log('[UserCenter] 步骤0: 权限检查通过');
    } catch (e) {
      console.error('[UserCenter] 步骤0: 权限检查失败', e);
      const errorMsg = e.message || '权限检查失败';
      this.setData({
        scanError: errorMsg,
        isScanning: false,
      });
      
      // 构建提示信息
      let message = '为了正常连接设备，请开启以下功能：\n\n';
      if (errorMsg.includes('蓝牙')) {
        message += '• 蓝牙\n';
      }
      if (errorMsg.includes('定位')) {
        message += '• 定位服务\n';
      }
      if (errorMsg.includes('权限')) {
        message += '• 定位权限\n';
      }
      message += '\n点击确定前往设置页面开启。';
      
      wx.showModal({
        title: '需要开启功能',
        content: message,
        confirmText: '去设置',
        cancelText: '取消',
        success: (res) => {
          if (res.confirm) {
            wx.openSetting({
              success: (settingRes) => {
                console.log('[UserCenter] 设置页面返回:', settingRes.authSetting);
              },
            });
          }
        },
      });
      
      return;
    }

    // 清空设备列表
    this.setData({
      deviceList: [],
      isScanning: false,
      scanError: null,
    });

    // 步骤1: 检查适配器状态（步骤0中已经初始化，这里直接检查状态）
    try {
      console.log('[UserCenter] 步骤1: 检查适配器状态...');
      const adapterState = await this.checkBluetoothAdapterState();
      if (!adapterState.available) {
        throw new Error('蓝牙适配器不可用');
      }
      console.log('[UserCenter] 步骤1: 适配器状态正常', adapterState);
    } catch (e) {
      console.error('[UserCenter] 步骤1: 适配器状态检查失败', e);
      this.setData({
        isScanning: false,
        scanError: e.message || '蓝牙适配器不可用',
      });
      Toast({
        context: this,
        selector: '#t-toast',
        message: e.message || '蓝牙适配器不可用',
        duration: 3000,
      });
      return;
    }

    // 步骤2: 开始扫描
    this.setData({
      isScanning: true,
      scanError: null,
    });

    const deviceMap = new Map();
    let scanStartTime = Date.now();
    let deviceCount = 0;

    try {
      console.log('[UserCenter] 步骤2: 启动设备扫描...');
      await bluetoothService.startScan((device) => {
        deviceCount++;
        console.log(`[UserCenter] 发现设备 #${deviceCount}:`, device);
        
        // 去重并更新列表
        if (!deviceMap.has(device.deviceId)) {
          deviceMap.set(device.deviceId, device);
          const deviceList = this._sortDeviceList(Array.from(deviceMap.values()));
          this.setData({
            deviceList,
          });
          console.log(`[UserCenter] 当前已发现 ${deviceList.length} 个设备`);
        } else {
          const existingDevice = deviceMap.get(device.deviceId);
          if (existingDevice.RSSI !== device.RSSI) {
            existingDevice.RSSI = device.RSSI;
            const deviceList = this._sortDeviceList(Array.from(deviceMap.values()));
            this.setData({
              deviceList,
            });
          }
        }
      });
      console.log('[UserCenter] 步骤2: 扫描启动成功');
    } catch (err) {
      console.error('[UserCenter] 步骤2: 扫描启动失败', err);
      const errorMsg = err.errorMessage || err.errMsg || '扫描失败';
      this.setData({
        isScanning: false,
        scanError: errorMsg,
      });
      
      wx.showModal({
        title: '扫描启动失败',
        content: `错误代码: ${err.errCode || '未知'}\n错误信息: ${errorMsg}\n\n可能的原因：\n1. 蓝牙未开启\n2. 没有授权蓝牙权限\n3. 未开启定位服务（Android需要）\n4. 系统不支持蓝牙`,
        showCancel: false,
      });
      
      return;
    }

    // 10秒后自动停止扫描
    this.data.scanTimer = setTimeout(() => {
      const scanDuration = Date.now() - scanStartTime;
      const foundCount = deviceMap.size;
      console.log(`[UserCenter] 扫描完成，耗时: ${scanDuration}ms，发现设备: ${foundCount}个`);
      
      this.stopScan();
      
      if (foundCount === 0) {
        wx.showModal({
          title: '扫描完成',
          content: `扫描完成，未发现任何设备\n\n扫描耗时: ${Math.round(scanDuration / 1000)}秒\n\n可能的原因：\n1. 附近没有蓝牙设备\n2. 设备未开启或不在范围内\n\n建议：\n1. 确保设备已开启并处于可发现状态\n2. 将设备靠近手机\n3. 查看控制台日志了解详细信息`,
          showCancel: false,
        });
      } else {
        Toast({
          context: this,
          selector: '#t-toast',
          message: `扫描完成，发现 ${foundCount} 个设备`,
        });
      }
    }, 10000);
  },

  // 设备列表排序：显示所有可连接设备，C6 排最前，其余按 RSSI 强弱
  _sortDeviceList(list) {
    return list
      .slice()
      .sort((a, b) => {
        const aIsC6 = (a.name || '').trim() === 'C6' ? 1 : 0;
        const bIsC6 = (b.name || '').trim() === 'C6' ? 1 : 0;
        if (bIsC6 !== aIsC6) return bIsC6 - aIsC6;
        return (b.RSSI || -100) - (a.RSSI || -100);
      });
  },

  // 检查蓝牙适配器状态
  checkBluetoothAdapterState() {
    return new Promise((resolve, reject) => {
      wx.getBluetoothAdapterState({
        success: (res) => {
          console.log('[BLE] 适配器状态:', res);
          resolve(res);
        },
        fail: (err) => {
          console.error('[BLE] 获取适配器状态失败', err);
          reject(err);
        },
      });
    });
  },

  // 停止扫描
  stopScan() {
    if (this.data.scanTimer) {
      clearTimeout(this.data.scanTimer);
      this.data.scanTimer = null;
    }
    bluetoothService.stopScan();
    this.setData({
      isScanning: false,
    });
  },

  // 设备列表项点击（可连接任意已发现设备）
  async onDeviceItemClick(e) {
    const device = e.currentTarget.dataset.device;
    if (!device || !device.deviceId) {
      return;
    }
    this._doConnectDevice(device);
  },

  async _doConnectDevice(device) {
    if (!device || !device.deviceId) return;

    if (this.data.isScanning) {
      this.stopScan();
    }

    if (this.data.deviceInfo.isConnected) {
      try {
        await bluetoothService.disconnect();
      } catch (e) {
        console.error('断开连接失败:', e);
      }
    }

    try {
      wx.showLoading({
        title: '连接中...',
        mask: true,
      });

      await bluetoothService.connectDevice(device.deviceId, device.name);

      wx.hideLoading();
    } catch (e) {
      wx.hideLoading();
      console.error('连接设备失败:', e);
      Toast({
        context: this,
        selector: '#t-toast',
        message: '连接失败，请重试',
      });
    }
  },

  // 断开连接
  async onDisconnectClick() {
    if (!this.data.deviceInfo.isConnected) {
      Toast({
        context: this,
        selector: '#t-toast',
        message: '当前未连接设备',
      });
      return;
    }

    wx.showModal({
      title: '提示',
      content: '确定要断开连接吗？',
      success: async (res) => {
        if (res.confirm) {
          try {
            await bluetoothService.disconnect();
            // 清除保存的设备信息
            wx.removeStorageSync('device_info');
            this.setData({
              deviceInfo: {
                isConnected: false,
                status: '未连接',
                deviceId: null,
                deviceName: null,
              },
            });
            Toast({
              context: this,
              selector: '#t-toast',
              message: '已断开连接',
            });
          } catch (e) {
            console.error('断开连接失败:', e);
            Toast({
              context: this,
              selector: '#t-toast',
              message: '断开连接失败',
            });
          }
        }
      },
    });
  },

  // 历史记录
  onHistoryClick() {
    wx.navigateTo({
      url: '/pages/history/index',
      fail: () => {
        Toast({
          context: this,
          selector: '#t-toast',
          message: '历史记录功能开发中',
        });
      },
    });
  },

  // 测试页面
  onTestClick() {
    if (!this.data.deviceInfo.isConnected) {
      Toast({
        context: this,
        selector: '#t-toast',
        message: '请先连接设备',
      });
      return;
    }

    wx.navigateTo({
      url: '/pages/test/index',
      fail: () => {
        Toast({
          context: this,
          selector: '#t-toast',
          message: '测试页面功能开发中',
        });
      },
    });
  },
});
