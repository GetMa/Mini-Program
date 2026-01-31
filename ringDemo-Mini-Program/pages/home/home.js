// pages/home/home.js
import Toast from 'tdesign-miniprogram/toast/index';
import bluetoothService from '../../utils/ble/bluetooth.js';
import healthDataService from '../../utils/ble/healthDataService.js';

Page({
  data: {
    todayDate: '',
    greeting: '',
    heartRate: '--',
    oxygen: '--',
    temperature: '--',
    battery: '--',
    heartRateTime: '',
    oxygenTime: '',
    temperatureTime: '',
    batteryTime: '',
    activityStepsText: '—',
    activityHeartRateText: '—',
    sleepText: '—',
    showMeasurementDialog: false,
    measurementTitle: '测量中',
    measurementProgress: 0,
    isMeasuring: false, // 是否正在测量
    isConnected: false, // 设备连接状态
  },

  onLoad() {
    this.init();
  },

  onShow() {
    if (typeof this.getTabBar === 'function' && this.getTabBar()) {
      this.getTabBar().init();
    }
    this.checkDeviceConnection();
    this.updateHealthDataDisplay();
  },

  init() {
    this.updateDateAndGreeting();
    this.checkDeviceConnection();
    this.updateHealthDataDisplay();
  },

  // 检查设备连接状态（以蓝牙服务实时状态为准）
  checkDeviceConnection() {
    try {
      const state = bluetoothService.getConnectionState();
      const isConnected = !!state.isConnected;
      this.setData({ isConnected });
      console.log('[Home] 设备连接状态:', isConnected);
    } catch (e) {
      console.error('[Home] 检查设备连接状态失败:', e);
      this.setData({ isConnected: false });
    }
  },

  // 更新日期和问候语
  updateDateAndGreeting() {
    const now = new Date();
    const year = now.getFullYear();
    const month = String(now.getMonth() + 1).padStart(2, '0');
    const day = String(now.getDate()).padStart(2, '0');
    const weekdays = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六'];
    const weekday = weekdays[now.getDay()];
    
    this.setData({
      todayDate: `${year}年${month}月${day}日 ${weekday}`,
    });

    const hour = now.getHours();
    let greeting = '你好';
    if (hour < 6) {
      greeting = '凌晨好';
    } else if (hour < 9) {
      greeting = '早上好';
    } else if (hour < 12) {
      greeting = '上午好';
    } else if (hour < 14) {
      greeting = '中午好';
    } else if (hour < 18) {
      greeting = '下午好';
    } else if (hour < 22) {
      greeting = '晚上好';
    } else {
      greeting = '夜深了';
    }

    // 获取用户名
    const userInfo = wx.getStorageSync('user_info') || {};
    const userName = userInfo.user_name || '川哥';
    
    this.setData({
      greeting: `${greeting}，${userName}`,
    });
  },

  // 更新健康数据显示（仅连接时显示该戒指 MAC/deviceId 对应的最后一次测量数据）
  updateHealthDataDisplay() {
    try {
      const state = bluetoothService.getConnectionState();
      const isConnected = !!state.isConnected;
      const deviceId = state && state.deviceId;
      if (!isConnected) {
        this.setData({
          heartRate: '--',
          heartRateTime: '',
          oxygen: '--',
          oxygenTime: '',
          temperature: '--',
          temperatureTime: '',
          battery: '--',
          batteryTime: '',
          activityStepsText: '—',
          activityHeartRateText: '—',
          sleepText: '—',
        });
        return;
      }
      const healthData = healthDataService.getHealthDataForDevice(deviceId);
      const measured = healthDataService.getSessionMeasured();
      const has = (v) => v && v !== '-' && v !== '--';

      // 仅在本连接会话内测量成功后才显示；连接后为 无，点击测量并拿到戒指数据才展示
      // 心率
      if (measured.heart_rate && has(healthData.heart_rate) && healthData.heart_rate_time) {
        this.setData({
          heartRate: healthData.heart_rate,
          heartRateTime: `测量时间: ${healthData.heart_rate_time}`,
          activityHeartRateText: `${healthData.heart_rate} 次/分`,
        });
      } else {
        this.setData({ heartRate: '--', heartRateTime: '', activityHeartRateText: '—' });
      }

      // 血氧
      if (measured.oxygen && has(healthData.oxygen) && healthData.oxygen_time) {
        this.setData({
          oxygen: healthData.oxygen,
          oxygenTime: `测量时间: ${healthData.oxygen_time}`,
        });
      } else {
        this.setData({ oxygen: '--', oxygenTime: '' });
      }

      // 体温
      if (measured.temperature && has(healthData.temperature) && healthData.temperature_time) {
        this.setData({
          temperature: healthData.temperature,
          temperatureTime: `测量时间: ${healthData.temperature_time}`,
        });
      } else {
        this.setData({ temperature: '--', temperatureTime: '' });
      }

      // 电池
      if (measured.battery && has(healthData.battery) && healthData.battery_time) {
        this.setData({
          battery: healthData.battery,
          batteryTime: `更新时间: ${healthData.battery_time}`,
        });
      } else {
        this.setData({ battery: '--', batteryTime: '' });
      }

      // 每日活动步数/热量、睡眠（暂无数据源，保持 —）
      this.setData({
        activityStepsText: '—',
        sleepText: '—',
      });
    } catch (e) {
      console.error('加载健康数据失败:', e);
    }
  },

  _showConnectFirstModal() {
    wx.showModal({
      title: '提示',
      content: '请先连接设备',
      confirmText: '去连接',
      cancelText: '取消',
      success: (res) => {
        if (res.confirm) wx.switchTab({ url: '/pages/usercenter/index' });
      },
    });
  },

  /** 佩戴检测：弹窗确认已佩戴后再继续，未佩戴不进行测量/获取 */
  _ensureWearingConfirmed(forBattery) {
    return new Promise((resolve, reject) => {
      wx.showModal({
        title: '请先佩戴戒指',
        content: '请确保已将戒指正确佩戴在手指上，否则无法获取准确数据。确认已佩戴后再开始。',
        confirmText: forBattery ? '获取电量' : '开始测量',
        cancelText: '取消',
        success: (res) => { if (res.confirm) resolve(); else reject(); },
      });
    });
  },

  // 心率点击
  onHeartRateClick() {
    if (!bluetoothService.getConnectionState().isConnected) {
      this._showConnectFirstModal();
      return;
    }
    this._ensureWearingConfirmed(false).then(() => this.startMeasurement('heart_rate', '测量心率')).catch(() => {});
  },

  // 血氧点击
  onOxygenClick() {
    if (!bluetoothService.getConnectionState().isConnected) {
      this._showConnectFirstModal();
      return;
    }
    this._ensureWearingConfirmed(false).then(() => this.startMeasurement('oxygen', '测量血氧')).catch(() => {});
  },

  // 体温点击
  onTemperatureClick() {
    if (!bluetoothService.getConnectionState().isConnected) {
      this._showConnectFirstModal();
      return;
    }
    this._ensureWearingConfirmed(false).then(() => this.startMeasurement('temperature', '测量体温')).catch(() => {});
  },

  // 电池点击
  onBatteryClick() {
    if (!bluetoothService.getConnectionState().isConnected) {
      this._showConnectFirstModal();
      return;
    }
    this._ensureWearingConfirmed(true).then(() => this.getBatteryLevel()).catch(() => {});
  },

  // AI医生点击
  onAIDoctorClick() {
    wx.navigateTo({
      url: '/pages/history/index',
      fail: () => {
        Toast({
          context: this,
          selector: '#t-toast',
          message: 'AI医生功能开发中',
        });
      },
    });
  },

  // 再生计划点击
  onRecoveryClick() {
    wx.navigateTo({
      url: '/pages/sleep/index',
      fail: () => {
        Toast({
          context: this,
          selector: '#t-toast',
          message: '再生计划功能开发中',
        });
      },
    });
  },

  // 活动点击
  onActivityClick() {
    wx.navigateTo({
      url: '/pages/test/index',
      fail: () => {
        Toast({
          context: this,
          selector: '#t-toast',
          message: '活动功能开发中',
        });
      },
    });
  },

  // 睡眠点击
  onSleepClick() {
    wx.navigateTo({
      url: '/pages/sleep/index',
      fail: () => {
        Toast({
          context: this,
          selector: '#t-toast',
          message: '睡眠功能开发中',
        });
      },
    });
  },

  // 历史报告点击
  onHistoryReportClick() {
    wx.navigateTo({
      url: '/pages/history/index',
      fail: () => {
        Toast({
          context: this,
          selector: '#t-toast',
          message: '历史报告功能开发中',
        });
      },
    });
  },

  // 开始测量
  async startMeasurement(type, title) {
    if (!bluetoothService.getConnectionState().isConnected) {
      this._showConnectFirstModal();
      return;
    }
    try {
      healthDataService.checkConnection();
    } catch (e) {
      this._showConnectFirstModal();
      return;
    }

    // 检查是否正在测量
    if (this.data.isMeasuring) {
      Toast({
        context: this,
        selector: '#t-toast',
        message: '正在测量中，请等待完成',
      });
      return;
    }

    // 显示测量对话框
    this.setData({
      showMeasurementDialog: true,
      measurementTitle: title,
      measurementProgress: 0,
      isMeasuring: true,
    });

    try {
      // 根据类型调用不同的测量方法
      if (type === 'heart_rate') {
        await healthDataService.measureHeartRate(
          (progress) => {
            // 进度回调
            this.setData({
              measurementProgress: progress,
            });
          },
          (heart, heartRota, stress, temp) => {
            // 结果回调
            this.setData({
              showMeasurementDialog: false,
              measurementProgress: 0,
              isMeasuring: false,
            });
            Toast({
              context: this,
              selector: '#t-toast',
              message: `心率测量完成: ${heart} bpm`,
            });
            this.updateHealthDataDisplay();
          },
          (code) => {
            this.setData({
              showMeasurementDialog: false,
              measurementProgress: 0,
              isMeasuring: false,
            });
            const msg = code === 10019 ? '测量超时，请确保已正确佩戴戒指后重试' : `心率测量失败，错误代码: ${code}`;
            Toast({ context: this, selector: '#t-toast', message: msg });
          }
        );
      } else if (type === 'oxygen') {
        await healthDataService.measureOxygen(
          (progress) => {
            this.setData({
              measurementProgress: progress,
            });
          },
          (heart, oxygen, temp) => {
            this.setData({
              showMeasurementDialog: false,
              measurementProgress: 0,
              isMeasuring: false,
            });
            Toast({
              context: this,
              selector: '#t-toast',
              message: `血氧测量完成: ${oxygen}%`,
            });
            this.updateHealthDataDisplay();
          },
          (code) => {
            this.setData({
              showMeasurementDialog: false,
              measurementProgress: 0,
              isMeasuring: false,
            });
            const msg = code === 10019 ? '测量超时，请确保已正确佩戴戒指后重试' : `血氧测量失败，错误代码: ${code}`;
            Toast({ context: this, selector: '#t-toast', message: msg });
          }
        );
      } else if (type === 'temperature') {
        await healthDataService.measureTemperature(
          (progress) => {
            this.setData({
              measurementProgress: progress,
            });
          },
          (temp) => {
            this.setData({
              showMeasurementDialog: false,
              measurementProgress: 0,
              isMeasuring: false,
            });
            Toast({
              context: this,
              selector: '#t-toast',
              message: `体温测量完成: ${temp}°C`,
            });
            this.updateHealthDataDisplay();
          },
          (code) => {
            this.setData({
              showMeasurementDialog: false,
              measurementProgress: 0,
              isMeasuring: false,
            });
            const msg = code === 10019 ? '测量超时，请确保已正确佩戴戒指后重试' : `体温测量失败，错误代码: ${code}`;
            Toast({ context: this, selector: '#t-toast', message: msg });
          }
        );
      }
    } catch (e) {
      console.error('测量失败:', e);
      this.setData({
        showMeasurementDialog: false,
        measurementProgress: 0,
        isMeasuring: false,
      });
      Toast({
        context: this,
        selector: '#t-toast',
        message: e.message || '测量失败',
      });
    }
  },

  // 获取电池电量
  async getBatteryLevel() {
    if (!bluetoothService.getConnectionState().isConnected) {
      this._showConnectFirstModal();
      return;
    }
    try {
      healthDataService.checkConnection();
    } catch (e) {
      this._showConnectFirstModal();
      return;
    }

    Toast({
      context: this,
      selector: '#t-toast',
      message: '正在获取电池电量...',
    });

    try {
      await healthDataService.getBattery(
        (battery) => {
          // 结果回调
          this.updateHealthDataDisplay();
          Toast({
            context: this,
            selector: '#t-toast',
            message: `电池电量: ${battery}%`,
          });
        },
        (code) => {
          const msg = code === 10008 ? '获取电池失败，请确保已正确佩戴戒指后重试' : `获取电池电量失败，错误代码: ${code}`;
          Toast({ context: this, selector: '#t-toast', message: msg });
        }
      );
    } catch (e) {
      console.error('获取电池电量失败:', e);
      Toast({
        context: this,
        selector: '#t-toast',
        message: e.message || '获取电池电量失败',
      });
    }
  },

  // 测量对话框变化
  onMeasurementDialogChange(e) {
    if (!e.detail.visible) {
      this.setData({
        showMeasurementDialog: false,
        measurementProgress: 0,
      });
    }
  },
});
