// utils/ble/healthDataService.js
// 健康数据传输服务

import bluetoothService from './bluetooth.js';

// 戒指 BLE 服务 UUID（与 ringDemo/APP 中 BLEService、ChipletRing AAR 一致）
const RING_SERVICE = 'BAE80001-4F05-4503-8E65-3AF1F7329D1F';
// 写特征 BAE80010，通知特征 BAE80011（见 BLEService.RBL_DEVICE_TX_UUID / RBL_DEVICE_RX_UUID）

// 指令字节：来自 LmAPI 的 CMD_* 常量（javap -v com.lm.sdk.LmAPI）
// CMD_GET_BATTERY=18(0x12), CMD_GET_HEART_ROTA=49(0x31), CMD_GET_HEART_Q2=50(0x32), CMD_TEMP_RESULT=52(0x34)
// 请求格式为 [cmd, ...params]，与 APP 的 LmAPI.GET_* / READ_TEMP 调用保持一致
const CMD = {
  HEART: [0x31, 0x01, 0x30],       // GET_HEART_ROTA(0x01, 0x30)
  OXYGEN: [0x32, 0x01],            // GET_HEART_Q2(0x01)
  TEMP: [0x34],                    // READ_TEMP，响应类型 CMD_TEMP_RESULT=0x34
  BATTERY: [0x12, 0x00],           // GET_BATTERY(0x00)
};
// 响应包可能带首字节 cmd 回显，解析时兼容 [cmd, ...payload] 与 纯 [...payload]
const CMD_RSP = { BATTERY: 0x12, HEART: 0x31, OXYGEN: 0x32, TEMP: 0x34 };

// 若 true：先 Notify 再握手；若 false：先握手再 Notify。C6 戒指在 Notify 后 1000ms 内未收到握手会断开；真机若先握手仍收不到 Notify 可改为 true 并缩短握手延迟。
const NOTIFY_FIRST = false;
// 原厂 APP 常用 writeNoResponse 发指令，戒指可能仅对 writeNoResponse 回包；真机可优先尝试 writeNoResponse
const USE_WRITE_NO_RESPONSE = true;

/**
 * 健康数据传输服务
 * 通过 BLE 与戒指设备通信，实现心率、血氧、体温、电池；协议按 ringDemo/APP 的 LmAPI、BLEService、
 * HistoryDataBean、IResponseListener 与 ChipletRing AAR 的 CMD_* 常量及字段布局实现。
 */
class HealthDataService {
  constructor() {
    this.isMeasuring = false;
    this.currentMeasurementType = null;
    this.measurementProgress = 0;
    this.onProgressCallback = null;
    this.onResultCallback = null;
    this.onErrorCallback = null;
    this._progressTimer = null;
    this._timeoutTimer = null;
    this._waitingForBattery = false;
    this._batteryTimeoutId = null;
    this._notifyCharId = null;
    this._batteryOnResult = null;
    this._batteryOnError = null;
    this._lastAppConnectDeviceId = null; // 每个连接后只发一次 APP_CONNECT
    this._lastAppBindDeviceId = null;    // 每个连接后只发一次 APP_BIND（原厂 TestActivity2 顺序：APP_BIND -> APP_CONNECT -> APP_REFRESH）
    this._lastAppRefreshDeviceId = null; // 每个连接后只发一次 APP_REFRESH
    this._sessionMeasured = {};          // 本次连接会话内已测量成功的 key，仅此时才在首页显示
    this._shownC6TimeoutTip = false;     // 本次连接是否已提示过“连 C6 未收到数据，请试 U-RFR”
  }

  _maybeShowC6TimeoutTip() {
    // C6 是戒指，不提示；若有其他非戒指设备连上且超时，可在此扩展提示
  }

  _ab2arr(value) {
    if (!value || !(value instanceof ArrayBuffer)) return [];
    return Array.from(new Uint8Array(value));
  }

  _hex(bytes) {
    return Array.from(bytes).map(b => ('0' + ((b) & 0xFF).toString(16)).slice(-2)).join(' ');
  }

  _finishMeasure() {
    if (this._progressTimer) { clearInterval(this._progressTimer); this._progressTimer = null; }
    if (this._timeoutTimer) { clearTimeout(this._timeoutTimer); this._timeoutTimer = null; }
    this.isMeasuring = false;
    this.currentMeasurementType = null;
  }

  _finishBattery() {
    if (this._batteryTimeoutId) { clearTimeout(this._batteryTimeoutId); this._batteryTimeoutId = null; }
    if (this._batteryDiagInterval) { clearInterval(this._batteryDiagInterval); this._batteryDiagInterval = null; }
    this._waitingForBattery = false;
  }

  /**
   * 连接后延时约 3 秒再发业务指令；指令之间间隔至少 400ms（按 ringDemo 协议要求）
   */
  async _waitReady() {
    const state = bluetoothService.getConnectionState();
    const now = Date.now();
    const elapsedConnect = state.connectedAt != null ? now - state.connectedAt : null;
    const elapsedWrite = (state.lastWriteAt != null && state.lastWriteAt > 0) ? now - state.lastWriteAt : null;
    console.log('[HealthData] _waitReady', { connectedAt: state.connectedAt, lastWriteAt: state.lastWriteAt, elapsedConnect, elapsedWrite, mode: this.currentMeasurementType || (this._waitingForBattery ? 'battery' : '-') });

    let t = Date.now();
    if (state.connectedAt != null) {
      const elapsed = t - state.connectedAt;
      if (elapsed < 3000) {
        const ms = 3000 - elapsed;
        console.log('[HealthData] 连接后等待', ms, 'ms 再发指令');
        await new Promise(r => setTimeout(r, ms));
      } else {
        console.log('[HealthData] 连接后已过', elapsed, 'ms，无需等待');
      }
    } else {
      console.log('[HealthData] connectedAt 为空，跳过连接后等待');
    }
    t = Date.now();
    if (state.lastWriteAt != null && state.lastWriteAt > 0) {
      const elapsed = t - state.lastWriteAt;
      if (elapsed < 400) {
        const ms = 400 - elapsed;
        console.log('[HealthData] 距上次写入间隔', ms, 'ms');
        await new Promise(r => setTimeout(r, ms));
      } else {
        console.log('[HealthData] 距上次写入已', elapsed, 'ms，无需间隔等待');
      }
    } else {
      console.log('[HealthData] lastWriteAt 为 0 或空，跳过间隔等待');
    }
  }

  /**
   * 连接后发送一次 APP_BIND（LmAPI，原厂 TestActivity2 在 APP_CONNECT 前调用）。协议字节推测为 0xA1，无参。
   */
  async _sendAppBindOnce(writeCharId) {
    const st = bluetoothService.getConnectionState();
    if (!st.isConnected || !st.deviceId) {
      this._lastAppBindDeviceId = null;
      return;
    }
    if (this._lastAppBindDeviceId === st.deviceId) {
      console.log('[HealthData] APP_BIND 已发送过，跳过');
      return;
    }
    const packet = [0xA1];
    const wType = USE_WRITE_NO_RESPONSE ? 'writeNoResponse' : 'write';
    console.log('[HealthData] 发送 APP_BIND hex=', this._hex(packet), 'writeType=', wType);
    try {
      await bluetoothService.writeCharacteristicWithBytes(RING_SERVICE, writeCharId, packet, { writeType: wType });
      this._lastAppBindDeviceId = st.deviceId;
      await new Promise(r => setTimeout(r, 400));
    } catch (e) {
      console.warn('[HealthData] APP_BIND 写入失败', e);
    }
  }

  /**
   * 连接后发送一次 APP_CONNECT(0)（LmAPI CMD_APP=0xA0），与 TestActivity2 的 APP_BIND/APP_CONNECT 一致，部分固件需此握手后才稳定回包。
   * 格式：convertToBytes(0xA0, [1, unix8b, tz, 0, param4b])，param=0；简化 tz=8。
   */
  async _sendAppConnectOnce(writeCharId) {
    const st = bluetoothService.getConnectionState();
    if (!st.isConnected || !st.deviceId) {
      this._lastAppConnectDeviceId = null;
      return;
    }
    if (this._lastAppConnectDeviceId === st.deviceId) {
      console.log('[HealthData] APP_CONNECT 已发送过，跳过');
      return;
    }
    const t = Math.floor(Date.now() / 1000);
    const unix8 = [];
    let n = t;
    for (let i = 0; i < 8; i++) { unix8.push(n & 0xff); n = Math.floor(n / 256); }
    const tz = 8;
    const data = [1, ...unix8, tz, 0, 0, 0, 0, 0];
    const packet = [0xA0, ...data];
    const wType = USE_WRITE_NO_RESPONSE ? 'writeNoResponse' : 'write';
    console.log('[HealthData] 发送 APP_CONNECT hex=', this._hex(packet), 'writeType=', wType);
    try {
      await bluetoothService.writeCharacteristicWithBytes(RING_SERVICE, writeCharId, packet, { writeType: wType });
      this._lastAppConnectDeviceId = st.deviceId;
      await new Promise(r => setTimeout(r, 400));
    } catch (e) {
      console.warn('[HealthData] APP_CONNECT 写入失败', e);
    }
  }

  /**
   * 连接后发送一次 APP_REFRESH(0)（原厂 TestActivity2 在 APP_CONNECT 后调用 LmAPI.APP_REFRESH(0)，部分固件需此后才稳定推送 Notify）
   * 格式按常见协议猜测：0xA2 0x00；若设备不识别会忽略。
   */
  async _sendAppRefreshOnce(writeCharId) {
    const st = bluetoothService.getConnectionState();
    if (!st.isConnected || !st.deviceId) return;
    if (this._lastAppRefreshDeviceId === st.deviceId) {
      console.log('[HealthData] APP_REFRESH 已发送过，跳过');
      return;
    }
    const packet = [0xA2, 0x00];
    const wType = USE_WRITE_NO_RESPONSE ? 'writeNoResponse' : 'write';
    console.log('[HealthData] 发送 APP_REFRESH hex=', this._hex(packet), 'writeType=', wType);
    try {
      await bluetoothService.writeCharacteristicWithBytes(RING_SERVICE, writeCharId, packet, { writeType: wType });
      this._lastAppRefreshDeviceId = st.deviceId;
      await new Promise(r => setTimeout(r, 400));
    } catch (e) {
      console.warn('[HealthData] APP_REFRESH 写入失败', e);
    }
  }

  /**
   * 确保已开启 Notify 并注册统一的数据回调。
   * NOTIFY_FIRST=true：先 Notify 再握手；false：先握手再 Notify。
   * 若设备有 0x1812 心率服务，会同时订阅 0x2A37（诊断：验证能否收到任意 Notify）。
   */
  async _ensureNotifyAndCallback() {
    const { write, notify } = bluetoothService.getRingCharIds(RING_SERVICE);
    this._notifyCharId = notify;
    bluetoothService.setOnCharacteristicValueChange(this._onNotify.bind(this));
    console.log('[HealthData] 已设置 onCharacteristicValueChange 回调 notifyCharId=', (notify || '').slice(0, 18));
    if (NOTIFY_FIRST) {
      await bluetoothService.enableNotification(RING_SERVICE, notify);
      await new Promise(r => setTimeout(r, 200));
      await this._sendAppBindOnce(write);
      await this._sendAppConnectOnce(write);
      await this._sendAppRefreshOnce(write);
      await new Promise(r => setTimeout(r, 200));
    } else {
      await this._sendAppBindOnce(write);
      await this._sendAppConnectOnce(write);
      await this._sendAppRefreshOnce(write);
      await new Promise(r => setTimeout(r, 200));
      await bluetoothService.enableNotification(RING_SERVICE, notify);
      await new Promise(r => setTimeout(r, 200));
    }
    await bluetoothService.enableHrmNotifyIfAvailable();
    console.log('[HealthData] _ensureNotifyAndCallback 完成 顺序=' + (NOTIFY_FIRST ? 'Notify先' : '握手先') + ' 已发 APP_BIND+APP_CONNECT+APP_REFRESH');
  }

  /**
   * 统一 Notify 回调：按 CMD 在包内查找并解析（支持前导 00 2a 等，与真机 Notify 如 00 2a 12 01 64 00 一致）
   * 电池：BaseActivity 仅用 datum，status 接受 0 或 1（如充电）。IResponseListener.battery(byte status, byte datum)
   */
  _onNotify(value, serviceId, characteristicId) {
    const arr = this._ab2arr(value);
    const hexStr = arr.length ? this._hex(arr.slice(0, 48)) : '';
    const eq = ((a, b) => (a || '').toLowerCase() === (b || '').toLowerCase());
    console.log('[HealthData] _onNotify 收到', 'charId=' + (characteristicId || '').slice(0, 8) + '..', 'expect=' + (this._notifyCharId || '').slice(0, 8) + '..', 'match=' + eq(characteristicId, this._notifyCharId), 'len=' + arr.length, 'hex=' + hexStr);
    if (!eq(characteristicId, this._notifyCharId)) return;
    if (arr.length === 0) return;
    const u = (i) => (arr[i] & 0xFF);
    const le16 = (i) => u(i) | (u(i + 1) << 8);

    // 按 CMD 在包内查找解析，兼容前导字节（如 00 2a）及延迟到达的响应
    const idx = (cmd) => arr.findIndex((b, i) => (arr[i] & 0xFF) === cmd);

    // 1) 电池 0x12：在包内查找 [0x12, status, datum]。BaseActivity 只用 datum；status 接受 0、1
    const i12 = idx(CMD_RSP.BATTERY);
    if (i12 >= 0 && i12 + 3 <= arr.length) {
      const status = u(i12 + 1), datum = u(i12 + 2);
      if ((status === 0 || status === 1) && datum >= 0 && datum <= 100) {
        this.saveHealthData('battery', String(datum));
        console.log('[HealthData] 解析电池 CMD@' + i12, 'status=' + status, 'datum=' + datum);
        if (this._waitingForBattery) {
          this._finishBattery();
          if (this._batteryOnResult) this._batteryOnResult(datum);
          this._batteryOnResult = null; this._batteryOnError = null;
        }
        return;
      }
    }

    // 2) 心率 0x31：[0x31, heart, heartRota, yaLi, temp_lo, temp_hi]
    const i31 = idx(CMD_RSP.HEART);
    if (i31 >= 0 && i31 + 6 <= arr.length && this.currentMeasurementType === 'heart_rate') {
      const heart = u(i31 + 1), heartRota = u(i31 + 2), yaLi = u(i31 + 3), temp = le16(i31 + 4);
      if (heart >= 30 && heart <= 250 && temp >= 3500 && temp <= 4200) {
        const tempC = (temp / 100).toFixed(1);
        this.saveHealthData('heart_rate', String(heart));
        this.saveHealthData('temperature', String(tempC));
        console.log('[HealthData] 解析心率 CMD@' + i31, heart, heartRota, yaLi, tempC);
        this._finishMeasure();
        if (this.onResultCallback) this.onResultCallback(heart, heartRota, yaLi, parseFloat(tempC));
        this.onResultCallback = null; this.onErrorCallback = null;
        return;
      }
    }

    // 3) 血氧 0x32：[0x32, heart, q2, temp_lo, temp_hi]
    const i32 = idx(CMD_RSP.OXYGEN);
    if (i32 >= 0 && i32 + 5 <= arr.length && this.currentMeasurementType === 'oxygen') {
      const heart = u(i32 + 1), q2 = u(i32 + 2), temp = le16(i32 + 3);
      if (q2 >= 50 && q2 <= 100 && temp >= 3500 && temp <= 4200) {
        const tempC = (temp / 100).toFixed(1);
        this.saveHealthData('oxygen', String(q2));
        this.saveHealthData('temperature', String(tempC));
        console.log('[HealthData] 解析血氧 CMD@' + i32, heart, q2, tempC);
        this._finishMeasure();
        if (this.onResultCallback) this.onResultCallback(heart, q2, parseFloat(tempC));
        this.onResultCallback = null; this.onErrorCallback = null;
        return;
      }
    }

    // 4) 体温 0x34：[0x34, temp_lo, temp_hi]
    const i34 = idx(CMD_RSP.TEMP);
    if (i34 >= 0 && i34 + 3 <= arr.length && this.currentMeasurementType === 'temperature') {
      const temp = le16(i34 + 1);
      if (temp >= 3500 && temp <= 4200) {
        const tempC = (temp / 100).toFixed(1);
        this.saveHealthData('temperature', String(tempC));
        console.log('[HealthData] 解析体温 CMD@' + i34, tempC);
        this._finishMeasure();
        if (this.onResultCallback) this.onResultCallback(parseFloat(tempC));
        this.onResultCallback = null; this.onErrorCallback = null;
        return;
      }
    }
    console.log('[HealthData] Notify 未匹配到有效 CMD 或范围 len=' + arr.length + ' hex=' + this._hex(arr.slice(0, 32)));
  }

  /** 连接成功后调用：本会话内尚未测量，首页全部显示 无；只在本次连接下测量成功后再显示。 */
  clearSessionMeasured() {
    this._sessionMeasured = {};
    this._shownC6TimeoutTip = false;
    console.log('[HealthData] clearSessionMeasured 连接后置空，首页将显示 无 直至测量成功');
  }

  /** 本连接会话内已测量成功的 key。 */
  getSessionMeasured() {
    return { ...this._sessionMeasured };
  }

  /**
   * 检查设备连接状态
   */
  checkConnection() {
    const deviceInfo = wx.getStorageSync('device_info');
    if (!deviceInfo || !deviceInfo.isConnected) {
      throw new Error('设备未连接，请先连接设备');
    }
    return true;
  }

  /**
   * 保存健康数据到本地存储（按设备 deviceId/MAC 分桶，连接戒指后只显示该戒指的最后一次测量数据）
   */
  saveHealthData(key, value) {
    try {
      const state = bluetoothService.getConnectionState();
      const deviceId = (state && state.deviceId) || 'unknown';
      const byDevice = wx.getStorageSync('health_data_by_device') || {};
      const entry = byDevice[deviceId] || {};
      const now = new Date();
      const timeStr = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')} ${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}:${String(now.getSeconds()).padStart(2, '0')}`;

      entry[key] = value;
      entry[`${key}_time`] = timeStr;
      byDevice[deviceId] = entry;

      this._sessionMeasured[key] = true;
      wx.setStorageSync('health_data_by_device', byDevice);
      console.log(`[HealthData] 保存数据 [${deviceId}]: ${key} = ${value} (${timeStr})`);
    } catch (e) {
      console.error('[HealthData] 保存健康数据失败:', e);
      throw e;
    }
  }

  /**
   * 获取指定设备（MAC/deviceId）对应的最后一次测量数据；若无则回退到旧版 health_data
   */
  getHealthDataForDevice(deviceId) {
    const byDevice = wx.getStorageSync('health_data_by_device') || {};
    const entry = byDevice[deviceId] || {};
    if (Object.keys(entry).length === 0) {
      const legacy = wx.getStorageSync('health_data') || {};
      if (Object.keys(legacy).length > 0) return legacy;
    }
    return entry;
  }

  /**
   * 测量心率（仅使用戒指通过 BLE 回传的真实数据，不生成模拟数据）
   * 若设备未返回有效数据则 onError(10019)。佩戴检测由调用方在测量前弹窗确认。
   */
  async measureHeartRate(onProgress, onResult, onError) {
    this.checkConnection();
    if (this.isMeasuring) throw new Error('正在测量中，请等待完成');

    this.isMeasuring = true;
    this.currentMeasurementType = 'heart_rate';
    this.onProgressCallback = onProgress;
    this.onResultCallback = onResult;
    this.onErrorCallback = onError;
    this.measurementProgress = 0;

    console.log('[HealthData] 开始测量心率，等待戒指返回真实数据...');

    try {
      await this._ensureNotifyAndCallback();
      await this._waitReady();
      const { writeNoRsp } = bluetoothService.getRingCharIds(RING_SERVICE);
      const wType = USE_WRITE_NO_RESPONSE ? 'writeNoResponse' : 'write';
      console.log('[HealthData] 即将发送 HEART，30s 内应有 onBLECharacteristicValueChange writeType=', wType);
      console.log('[HealthData] 写入 HEART hex=', this._hex(CMD.HEART));
      await bluetoothService.writeCharacteristicWithBytes(RING_SERVICE, writeNoRsp, CMD.HEART, { writeType: wType });
    } catch (e) {
      console.error('[HealthData] 心率写入/订阅失败', e);
      this._finishMeasure();
      if (onError) onError(10019);
      return;
    }

    let progress = 0;
    this._progressTimer = setInterval(() => {
      if (!this.isMeasuring) return;
      progress = Math.min(progress + 5, 90);
      this.measurementProgress = progress;
      if (this.onProgressCallback) this.onProgressCallback(progress);
    }, 1500);

    this._timeoutTimer = setTimeout(() => {
      if (!this.isMeasuring) return;
      this._finishMeasure();
      console.log('[HealthData] 心率测量超时，未收到戒指数据（若无 onBLECharacteristicValueChange 则 Notify 未生效）');
      this._maybeShowC6TimeoutTip();
      if (this.onErrorCallback) this.onErrorCallback(10019);
      this.onErrorCallback = null; this.onResultCallback = null;
    }, 30000);
  }

  /**
   * 测量血氧（仅使用戒指 BLE 回传的真实数据，不生成模拟数据）
   * 若无设备数据则 onError(10019)。佩戴检测由调用方在测量前弹窗确认。
   */
  async measureOxygen(onProgress, onResult, onError) {
    this.checkConnection();
    if (this.isMeasuring) throw new Error('正在测量中，请等待完成');

    this.isMeasuring = true;
    this.currentMeasurementType = 'oxygen';
    this.onProgressCallback = onProgress;
    this.onResultCallback = onResult;
    this.onErrorCallback = onError;
    this.measurementProgress = 0;

    console.log('[HealthData] 开始测量血氧，等待戒指返回真实数据...');

    try {
      await this._ensureNotifyAndCallback();
      await this._waitReady();
      const { writeNoRsp } = bluetoothService.getRingCharIds(RING_SERVICE);
      const wType = USE_WRITE_NO_RESPONSE ? 'writeNoResponse' : 'write';
      console.log('[HealthData] 写入 OXYGEN hex=', this._hex(CMD.OXYGEN), 'writeType=', wType);
      await bluetoothService.writeCharacteristicWithBytes(RING_SERVICE, writeNoRsp, CMD.OXYGEN, { writeType: wType });
    } catch (e) {
      console.error('[HealthData] 血氧写入/订阅失败', e);
      this._finishMeasure();
      if (onError) onError(10019);
      return;
    }

    let progress = 0;
    this._progressTimer = setInterval(() => {
      if (!this.isMeasuring) return;
      progress = Math.min(progress + 10, 90);
      this.measurementProgress = progress;
      if (this.onProgressCallback) this.onProgressCallback(progress);
    }, 2000);

    this._timeoutTimer = setTimeout(() => {
      if (!this.isMeasuring) return;
      this._finishMeasure();
      console.log('[HealthData] 血氧测量超时，未收到戒指数据（若无 onBLECharacteristicValueChange 则 Notify 未生效）');
      this._maybeShowC6TimeoutTip();
      if (this.onErrorCallback) this.onErrorCallback(10019);
      this.onErrorCallback = null; this.onResultCallback = null;
    }, 20000);
  }

  /**
   * 测量体温（仅使用戒指 BLE 回传的真实数据，不生成模拟数据）
   * 若无设备数据则 onError(10019)。佩戴检测由调用方在测量前弹窗确认。
   */
  async measureTemperature(onProgress, onResult, onError) {
    this.checkConnection();
    if (this.isMeasuring) throw new Error('正在测量中，请等待完成');

    this.isMeasuring = true;
    this.currentMeasurementType = 'temperature';
    this.onProgressCallback = onProgress;
    this.onResultCallback = onResult;
    this.onErrorCallback = onError;
    this.measurementProgress = 0;

    console.log('[HealthData] 开始测量体温，等待戒指返回真实数据...');

    try {
      await this._ensureNotifyAndCallback();
      await this._waitReady();
      const { writeNoRsp } = bluetoothService.getRingCharIds(RING_SERVICE);
      const wType = USE_WRITE_NO_RESPONSE ? 'writeNoResponse' : 'write';
      console.log('[HealthData] 写入 TEMP hex=', this._hex(CMD.TEMP), 'writeType=', wType);
      await bluetoothService.writeCharacteristicWithBytes(RING_SERVICE, writeNoRsp, CMD.TEMP, { writeType: wType });
    } catch (e) {
      console.error('[HealthData] 体温写入/订阅失败', e);
      this._finishMeasure();
      if (onError) onError(10019);
      return;
    }

    let progress = 0;
    this._progressTimer = setInterval(() => {
      if (!this.isMeasuring) return;
      progress = Math.min(progress + 20, 90);
      this.measurementProgress = progress;
      if (this.onProgressCallback) this.onProgressCallback(progress);
    }, 200);

    this._timeoutTimer = setTimeout(() => {
      if (!this.isMeasuring) return;
      this._finishMeasure();
      console.log('[HealthData] 体温测量超时，未收到戒指数据（若无 onBLECharacteristicValueChange 则 Notify 未生效）');
      this._maybeShowC6TimeoutTip();
      if (this.onErrorCallback) this.onErrorCallback(10019);
      this.onErrorCallback = null; this.onResultCallback = null;
    }, 25000);
  }

  /**
   * 获取电池电量（仅使用戒指 BLE 回传的真实数据，不生成模拟数据）
   * 若无设备数据则 onError(10008)。佩戴检测由调用方在获取前弹窗确认。
   */
  async getBattery(onResult, onError) {
    this.checkConnection();

    console.log('[HealthData] 开始获取电池电量，等待戒指返回真实数据...');

    this._waitingForBattery = true;
    this._batteryOnResult = onResult;
    this._batteryOnError = onError;

    try {
      await this._ensureNotifyAndCallback();
      await this._waitReady();
      const { writeNoRsp } = bluetoothService.getRingCharIds(RING_SERVICE);
      const wType = USE_WRITE_NO_RESPONSE ? 'writeNoResponse' : 'write';
      const st = bluetoothService.getConnectionState();
      console.log('[HealthData] 即将发送 BATTERY writeType=', wType, 'deviceId=', (st.deviceId || '').slice(0, 17), '60s 内应有 [BLE] *** onBLECharacteristicValueChange 被调用 ***');
      console.log('[HealthData] 写入 BATTERY hex=', this._hex(CMD.BATTERY));
      await bluetoothService.writeCharacteristicWithBytes(RING_SERVICE, writeNoRsp, CMD.BATTERY, { writeType: wType });
    } catch (e) {
      console.error('[HealthData] 电池写入/订阅失败', e);
      this._finishBattery();
      this._batteryOnResult = null; this._batteryOnError = null;
      if (onError) onError(10008);
      return;
    }

    const batteryStart = Date.now();
    this._batteryDiagInterval = setInterval(() => {
      if (!this._waitingForBattery) { clearInterval(this._batteryDiagInterval); this._batteryDiagInterval = null; return; }
      const st = bluetoothService.getConnectionState();
      const elapsed = Math.round((Date.now() - batteryStart) / 1000);
      console.log('[HealthData] 诊断: 仍在等待电池 Notify deviceId=' + (st.deviceId || '').slice(0, 17) + ' 已等待 ' + elapsed + 's 若始终无 onBLECharacteristicValueChange 则 Notify 未生效');
    }, 15000);
    this._batteryTimeoutId = setTimeout(() => {
      if (this._batteryDiagInterval) { clearInterval(this._batteryDiagInterval); this._batteryDiagInterval = null; }
      if (!this._waitingForBattery) return;
      this._finishBattery();
      console.log('[HealthData] 电池电量获取超时，未收到戒指数据');
      console.log('[HealthData] 超时诊断: 若整次会话无 [BLE] *** onBLECharacteristicValueChange 被调用 ***：1) 试 USE_WRITE_NO_RESPONSE=true 2) 试 NOTIFY_FIRST=true 3) 戒指固件/换机');
      this._maybeShowC6TimeoutTip();
      if (this._batteryOnError) this._batteryOnError(10008);
      this._batteryOnResult = null; this._batteryOnError = null;
    }, 60000);
  }

  /**
   * 停止当前测量
   */
  stopMeasurement() {
    if (this.isMeasuring) {
      this._finishMeasure();
      this.measurementProgress = 0;
      console.log('[HealthData] 停止测量');
    }
  }

  /**
   * 获取测量状态
   */
  getMeasurementState() {
    return {
      isMeasuring: this.isMeasuring,
      type: this.currentMeasurementType,
      progress: this.measurementProgress,
    };
  }
}

// 导出单例
const healthDataService = new HealthDataService();
export default healthDataService;
