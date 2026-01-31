// pages/health/index.js
import Toast from 'tdesign-miniprogram/toast/index';
import bluetoothService from '../../utils/ble/bluetooth.js';
import healthDataService from '../../utils/ble/healthDataService.js';

Page({
  data: {
    heartRate: '--',
    bloodOxygen: '--',
    stress: '--',
    bodyBattery: '--',
    lastUpdateTime: '暂无数据',
    connectedDeviceId: '', // 当前连接戒指的 deviceId/MAC，用于显示数据来源
    showMeasurementDialog: false,
    measurementTitle: '测量中',
    measurementProgress: 0,
    isMeasuring: false, // 是否正在测量
  },

  onLoad() {
    this.loadHealthData();
  },

  onShow() {
    this.loadHealthData();
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

  /** 佩戴检测：弹窗确认已佩戴后再开始测量 */
  _ensureWearingConfirmed() {
    return new Promise((resolve, reject) => {
      wx.showModal({
        title: '请先佩戴戒指',
        content: '请确保已将戒指正确佩戴在手指上，否则无法获取准确数据。确认已佩戴后再开始。',
        confirmText: '开始测量',
        cancelText: '取消',
        success: (res) => { if (res.confirm) resolve(); else reject(); },
      });
    });
  },

  // 加载健康数据（仅连接时显示该戒指 MAC/deviceId 对应的最后一次测量数据）
  loadHealthData() {
    try {
      const state = bluetoothService.getConnectionState();
      const isConnected = !!state.isConnected;
      const deviceId = state && state.deviceId;
      if (!isConnected) {
        this.setData({
          heartRate: '--',
          bloodOxygen: '--',
          stress: '--',
          bodyBattery: '--',
          lastUpdateTime: '暂无数据',
          connectedDeviceId: '',
        });
        return;
      }
      const healthData = healthDataService.getHealthDataForDevice(deviceId);
      const heartRate = healthData.heart_rate && healthData.heart_rate !== '-' && healthData.heart_rate !== '--' ? healthData.heart_rate : null;
      const oxygen = healthData.oxygen && healthData.oxygen !== '-' && healthData.oxygen !== '--' ? healthData.oxygen : null;
      const temperature = healthData.temperature && healthData.temperature !== '-' && healthData.temperature !== '--' ? healthData.temperature : null;

      if (heartRate && healthData.heart_rate_time) {
        this.setData({ heartRate: `${heartRate} 次/分` });
      } else {
        this.setData({ heartRate: '--' });
      }

      if (oxygen && healthData.oxygen_time) {
        this.setData({ bloodOxygen: `${oxygen} %` });
      } else {
        this.setData({ bloodOxygen: '--' });
      }

      this.setData({ stress: '--' });

      if (heartRate || oxygen || temperature) {
        const bodyBattery = this.calculateBodyBattery(heartRate || '-', oxygen || '-', temperature || '-');
        this.setData({ bodyBattery });
      } else {
        this.setData({ bodyBattery: '--' });
      }

      this.setData({
        lastUpdateTime: this.getLastUpdateTime(healthData),
        connectedDeviceId: deviceId || '',
      });
    } catch (e) {
      console.error('加载健康数据失败:', e);
    }
  },

  // 计算身体电量
  calculateBodyBattery(heartRate, oxygen, temperature) {
    if (heartRate === '-' && oxygen === '-' && temperature === '-') {
      return 50; // 默认值
    }

    let score = 0;
    let count = 0;

    // 心率评分 (正常范围: 60-100 bpm，理想值: 70-80)
    if (heartRate !== '-') {
      try {
        const hr = parseInt(heartRate);
        let heartScore = 100;
        if (hr < 60) {
          heartScore = Math.max(40, 100 - (60 - hr) * 2);
        } else if (hr > 100) {
          heartScore = Math.max(40, 100 - (hr - 100) * 2);
        } else if (hr >= 70 && hr <= 80) {
          heartScore = 100;
        } else {
          heartScore = 80 - Math.abs(hr - 75) * 2;
        }
        score += heartScore;
        count++;
      } catch (e) {
        // 忽略无效数据
      }
    }

    // 血氧评分 (正常范围: 95-100%，理想值: 98-100%)
    if (oxygen !== '-') {
      try {
        const ox = parseInt(oxygen);
        let oxygenScore = 100;
        if (ox < 95) {
          oxygenScore = Math.max(30, ox * 2);
        } else if (ox >= 98) {
          oxygenScore = 100;
        } else {
          oxygenScore = 80 + (ox - 95) * 4;
        }
        score += oxygenScore;
        count++;
      } catch (e) {
        // 忽略无效数据
      }
    }

    // 体温评分 (正常范围: 36.0-37.2°C，理想值: 36.5-36.8°C)
    if (temperature !== '-') {
      try {
        const temp = parseFloat(temperature);
        let tempScore = 100;
        if (temp < 36.0) {
          tempScore = Math.max(40, 40 + (temp - 35.0) * 20);
        } else if (temp > 37.2) {
          tempScore = Math.max(40, 100 - (temp - 37.2) * 30);
        } else if (temp >= 36.5 && temp <= 36.8) {
          tempScore = 100;
        } else {
          if (temp < 36.5) {
            tempScore = 80 + (temp - 36.0) * 40;
          } else {
            tempScore = 100 - (temp - 36.8) * 50;
          }
        }
        score += tempScore;
        count++;
      } catch (e) {
        // 忽略无效数据
      }
    }

    if (count > 0) {
      return Math.max(0, Math.min(100, Math.floor(score / count)));
    }

    return 50; // 默认值
  },

  // 获取最后更新时间
  getLastUpdateTime(healthData) {
    const times = [
      healthData.heart_rate_time,
      healthData.oxygen_time,
      healthData.temperature_time,
      healthData.battery_time,
    ];

    let latestTime = '';
    for (const time of times) {
      if (time && time !== '未测量') {
        if (!latestTime || time > latestTime) {
          latestTime = time;
        }
      }
    }

    if (latestTime) {
      // 格式化时间显示
      const date = new Date(latestTime);
      const year = date.getFullYear();
      const month = String(date.getMonth() + 1).padStart(2, '0');
      const day = String(date.getDate()).padStart(2, '0');
      const hour = String(date.getHours()).padStart(2, '0');
      const minute = String(date.getMinutes()).padStart(2, '0');
      return `${year}-${month}-${day} ${hour}:${minute}`;
    }

    return '暂无数据';
  },

  // 心率点击
  onHeartRateClick() {
    if (!bluetoothService.getConnectionState().isConnected) {
      this._showConnectFirstModal();
      return;
    }
    this._ensureWearingConfirmed().then(() => this.startMeasurement('heart_rate', '测量心率')).catch(() => {});
  },

  // 血氧点击
  onBloodOxygenClick() {
    if (!bluetoothService.getConnectionState().isConnected) {
      this._showConnectFirstModal();
      return;
    }
    this._ensureWearingConfirmed().then(() => this.startMeasurement('oxygen', '测量血氧')).catch(() => {});
  },

  // 压力点击
  onStressClick() {
    Toast({
      context: this,
      selector: '#t-toast',
      message: '压力测量功能开发中',
    });
  },

  // 身体电量点击
  onBodyBatteryClick() {
    this.loadHealthData(); // 重新计算身体电量
  },

  // 睡眠分析点击
  onSleepAnalysisClick() {
    wx.navigateTo({
      url: '/pages/sleep/index',
      fail: () => {
        Toast({
          context: this,
          selector: '#t-toast',
          message: '睡眠分析功能开发中',
        });
      },
    });
  },

  // 历史点击
  onHistoryClick() {
    wx.navigateTo({
      url: '/pages/history/index',
      fail: () => {
        Toast({
          context: this,
          selector: '#t-toast',
          message: '历史数据功能开发中',
        });
      },
    });
  },

  // 周期采集点击
  onCollectionClick() {
    Toast({
      context: this,
      selector: '#t-toast',
      message: '周期采集功能开发中',
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
            this.loadHealthData();
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
            this.loadHealthData();
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
            this.loadHealthData();
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
