// utils/ble/bluetooth.js
// 蓝牙服务模块

class BluetoothService {
  constructor() {
    this.adapterState = false; // 蓝牙适配器状态
    this.isScanning = false; // 是否正在扫描
    this.isConnected = false; // 是否已连接
    this.deviceId = null; // 连接的设备ID
    this.deviceName = null; // 设备名称
    this.deviceMac = null; // 设备MAC地址
    this.services = []; // 设备服务列表
    this.characteristics = {}; // 特征值列表
    this.onDeviceFoundCallback = null; // 设备发现回调
    this.onConnectedCallback = null; // 连接成功回调
    this.onDisconnectedCallback = null; // 断开连接回调
    this.onCharacteristicValueChangeCallback = null; // 特征值变化回调
    this._notifyEnabled = {}; // 已开启 notify 的特征 key: serviceId+characteristicId
    this.connectedAt = null;   // 连接成功时间戳，用于连接后 3 秒再发业务指令
    this.lastWriteAt = 0;     // 上次写入成功时间戳，用于指令间隔 400ms
  }

  /**
   * 解析 ArrayBuffer 为 Uint8Array
   */
  _ab2bytes(value) {
    if (!value || !(value instanceof ArrayBuffer)) return new Uint8Array(0);
    return new Uint8Array(value);
  }

  /**
   * 戒指协议：从 BAE80001 下按属性解析出 写/通知 特征 UUID。
   * 写特征优先用 p.write（带响应），其次 p.writeNoResponse。部分固件对 writeNoResponse 回 Notify 更稳定。
   * 若未发现则回退到约定 UUID：BAE80010(写)、BAE80011(通知)。
   */
  getRingCharIds(serviceId) {
    const RING_SVC = 'BAE80001-4F05-4503-8E65-3AF1F7329D1F';
    const FALLBACK_WRITE = 'BAE80010-4F05-4503-8E65-3AF1F7329D1F';
    const FALLBACK_NOTIFY = 'BAE80011-4F05-4503-8E65-3AF1F7329D1F';
    let list = this.characteristics[serviceId || RING_SVC] || [];
    if (list.length === 0) {
      const key = Object.keys(this.characteristics).find(k => this._normId(k).indexOf('bae80001') >= 0);
      if (key) list = this.characteristics[key] || [];
    }
    console.log('[BLE] getRingCharIds BAE80001 特征数=', list.length, '各特征:', list.map(c => c.uuid + ':' + JSON.stringify(c.properties || {})));
    let writeUuid = null;
    let writeNoRspUuid = null;
    let notifyUuid = null;
    for (const c of list) {
      const p = c.properties || {};
      if (p.write && !writeUuid) writeUuid = c.uuid;
      if (p.writeNoResponse && !writeNoRspUuid) writeNoRspUuid = c.uuid;
      if (p.notify && !notifyUuid) notifyUuid = c.uuid;
    }
    const write = writeUuid || writeNoRspUuid || FALLBACK_WRITE;
    const writeNoRsp = writeNoRspUuid || writeUuid || write;
    const notify = notifyUuid || FALLBACK_NOTIFY;
    console.log('[BLE] getRingCharIds write=', write, 'writeNoRsp=', writeNoRsp, 'notify=', notify);
    return { write, writeNoRsp, notify };
  }

  /** 规范化 deviceId 便于比较（去冒号、小写） */
  _normId(s) {
    return (s || '').replace(/:/g, '').toLowerCase();
  }

  /**
   * 注册/重绑 wx.onBLECharacteristicValueChange，每次调用都会覆盖之前（防止被其它逻辑覆盖）。
   * 在 enableNotification 前及 connect 后调用，确保收到 Notify 时能进入本回调。
   */
  _ensureValueChangeListener() {
    console.log('[BLE] >>> 绑定 wx.onBLECharacteristicValueChange（每次确保未被覆盖）<<<');
    wx.onBLECharacteristicValueChange((res) => {
      try {
        const vLen = (res && res.value && res.value.byteLength) || 0;
        const recvDeviceId = (res.deviceId || '').toString();
        const curDeviceId = (this.deviceId || '').toString();
        const normRecv = this._normId(recvDeviceId);
        const normCur = this._normId(curDeviceId);
        const match = normRecv === normCur;
        // 诊断：任何 Notify 触发都会打这条，用于确认小程序是否收到 BLE 回调
        console.log('[BLE] *** onBLECharacteristicValueChange 被调用 *** valueLen=' + vLen, 'charId=' + (res.characteristicId || '').substring(0, 24), 'serviceId=' + (res.serviceId || '').substring(0, 24), 'deviceIdMatch=' + match, 'recvId=' + recvDeviceId.slice(0, 17), 'curId=' + curDeviceId.slice(0, 17));
        if (!match) {
          console.log('[BLE] 忽略: deviceId 不匹配 normRecv=' + normRecv.slice(0, 12) + ' normCur=' + normCur.slice(0, 12));
          return;
        }
        if (!this.onCharacteristicValueChangeCallback) {
          console.log('[BLE] 忽略: 无 onCharacteristicValueChangeCallback（HealthData 未设置）');
          return;
        }
        if (vLen > 0 && res.value) {
          const arr = new Uint8Array(res.value);
          const hexHead = Array.from(arr.slice(0, 16)).map(b => ('0' + (b & 0xff).toString(16)).slice(-2)).join(' ');
          console.log('[BLE] Notify 数据首 16 字节 hex=', hexHead);
        }
        this.onCharacteristicValueChangeCallback(res.value, res.serviceId, res.characteristicId);
      } catch (e) {
        console.error('[BLE] onBLECharacteristicValueChange 内部异常', e);
      }
    });
    console.log('[BLE] wx.onBLECharacteristicValueChange 已绑定 当前 deviceId=', (this.deviceId || '').slice(0, 17));
  }

  // 初始化蓝牙适配器
  async initAdapter() {
    return new Promise((resolve, reject) => {
      console.log('[BLE] 开始初始化蓝牙适配器...');
      wx.openBluetoothAdapter({
        mode: 'central',
        success: (res) => {
          console.log('[BLE] 蓝牙适配器初始化成功', res);
          this.adapterState = true;
          this._ensureValueChangeListener();
          this.onAdapterStateChange();
          resolve(res);
        },
        fail: (err) => {
          console.error('[BLE] 蓝牙适配器初始化失败', err);
          this.adapterState = false;
          
          let errorMsg = '蓝牙适配器初始化失败';
          switch (err.errCode) {
            case 10001:
              errorMsg = '请打开手机蓝牙';
              break;
            case 10012:
              errorMsg = '当前蓝牙适配器不可用';
              break;
            case 10013:
              errorMsg = '系统不支持蓝牙功能';
              break;
            default:
              errorMsg = `初始化失败，错误代码: ${err.errCode}, 错误信息: ${err.errMsg || '未知错误'}`;
          }
          
          console.error('[BLE] 错误详情:', {
            errCode: err.errCode,
            errMsg: err.errMsg,
            errorMessage: errorMsg,
          });
          
          if (err.errCode === 10001) {
            wx.showModal({
              title: '提示',
              content: errorMsg,
              showCancel: false,
            });
          }
          
          reject({
            ...err,
            errorMessage: errorMsg,
          });
        },
      });
    });
  }

  // 监听适配器状态变化
  onAdapterStateChange() {
    wx.onBluetoothAdapterStateChange((res) => {
      console.log('[BLE] 蓝牙适配器状态变化', res);
      console.log('[BLE] 状态详情:', {
        available: res.available,
        discovering: res.discovering,
      });
      this.adapterState = res.available;
      if (!res.available && this.isScanning) {
        console.log('[BLE] 蓝牙不可用，停止扫描');
        this.stopScan();
      }
      if (!res.available && this.isConnected) {
        console.log('[BLE] 蓝牙不可用，断开连接');
        this.handleDisconnect();
      }
    });
  }

    // 开始扫描设备
  async startScan(onDeviceFound) {
    if (this.isScanning) {
      console.log('已经在扫描中');
      return Promise.resolve();
    }

    if (!this.adapterState) {
      try {
        await this.initAdapter();
      } catch (e) {
        throw new Error('蓝牙适配器未初始化');
      }
    }

    this.onDeviceFoundCallback = onDeviceFound;

    // 监听设备发现
    console.log('[BLE] 注册设备发现监听器');
    wx.onBluetoothDeviceFound((res) => {
      const devices = res.devices || [];
      console.log(`[BLE] 发现 ${devices.length} 个设备`);
      
      devices.forEach((device, index) => {
        console.log(`[BLE] 设备 ${index + 1}:`, {
          deviceId: device.deviceId,
          name: device.name,
          RSSI: device.RSSI,
          advertisData: device.advertisData ? `有广播数据(${device.advertisData.byteLength}字节)` : '无广播数据',
          advertisServiceUUIDs: device.advertisServiceUUIDs || [],
        });
        
        // 优化：解析设备信息，包括从广播数据中解析名称
        const deviceInfo = this.parseDeviceInfo(device);
        if (deviceInfo) {
          console.log(`[BLE] 设备 ${index + 1} 解析成功:`, deviceInfo);
          if (this.onDeviceFoundCallback) {
            this.onDeviceFoundCallback(deviceInfo);
          }
        } else {
          console.log(`[BLE] 设备 ${index + 1} 解析失败，不符合条件`);
        }
      });
    });

    // 开始扫描
    return new Promise((resolve, reject) => {
      console.log('[BLE] 开始启动设备扫描...');
      wx.startBluetoothDevicesDiscovery({
        allowDuplicatesKey: false, // 不重复上报同一设备
        interval: 0, // 上报间隔，0表示实时上报
        success: (res) => {
          console.log('[BLE] 设备扫描启动成功', res);
          this.isScanning = true;
          resolve(res);
        },
        fail: (err) => {
          console.error('[BLE] 设备扫描启动失败', err);
          this.isScanning = false;
          
          let errorMsg = '启动设备扫描失败';
          switch (err.errCode) {
            case 10001:
              errorMsg = '请打开手机蓝牙';
              break;
            case 10002:
              errorMsg = '连接已断开，请重试';
              break;
            case 10003:
              errorMsg = '没有权限，请授权蓝牙权限';
              break;
            case 10004:
              errorMsg = '当前蓝牙适配器不可用';
              break;
            case 10005:
              errorMsg = '当前设备不支持蓝牙功能';
              break;
            case 10006:
              errorMsg = '当前设备未开启定位服务';
              break;
            case 10007:
              errorMsg = '缺少必要的系统权限';
              break;
            case 10008:
              errorMsg = '系统错误';
              break;
            case 10009:
              errorMsg = 'Android系统特有错误';
              break;
            default:
              errorMsg = `扫描失败，错误代码: ${err.errCode}, 错误信息: ${err.errMsg || '未知错误'}`;
          }
          
          console.error('[BLE] 扫描失败详情:', {
            errCode: err.errCode,
            errMsg: err.errMsg,
            errorMessage: errorMsg,
          });
          
          reject({
            ...err,
            errorMessage: errorMsg,
          });
        },
      });
    });
  }

  // 解析设备信息，返回所有可连接蓝牙设备（不再仅限戒指设备）
  parseDeviceInfo(device) {
    if (!device || !device.deviceId) {
      return null;
    }

    // 尝试从设备名称获取
    let deviceName = device.name || '';
    
    // 如果没有名称，尝试从广播数据中解析
    if (!deviceName && device.advertisData) {
      deviceName = this.parseDeviceNameFromAdvertisData(device.advertisData);
    }

    // 返回所有设备信息，供列表展示与连接
    return {
      deviceId: device.deviceId,
      name: (deviceName && deviceName.trim()) ? deviceName.trim() : '未知设备',
      RSSI: device.RSSI,
      advertisData: device.advertisData,
      advertisServiceUUIDs: device.advertisServiceUUIDs || [],
      localName: deviceName,
    };
  }

  // 从广播数据中解析设备名称
  parseDeviceNameFromAdvertisData(advertisData) {
    if (!advertisData) {
      return '';
    }

    try {
      // 广播数据格式解析（参考BleAdParse.java）
      // 0x08: Short local device name
      // 0x09: Complete local device name
      const buffer = new Uint8Array(advertisData);
      let offset = 0;

      while (offset < buffer.length) {
        const length = buffer[offset];
        if (length === 0 || offset + length >= buffer.length) {
          break;
        }

        const type = buffer[offset + 1];
        
        // 0x08 或 0x09 表示设备名称
        if (type === 0x08 || type === 0x09) {
          const nameLength = length - 1;
          const nameBytes = buffer.slice(offset + 2, offset + 2 + nameLength);
          let name = '';
          for (let i = 0; i < nameBytes.length; i++) {
            name += String.fromCharCode(nameBytes[i]);
          }
          return name.trim();
        }

        offset += length + 1;
      }
    } catch (e) {
      console.error('解析广播数据失败:', e);
    }

    return '';
  }

  // 判断是否为戒指设备
  // 注意：由于无法使用Android SDK的LogicalApi.getBleDeviceInfoWhenBleScan
  // 这里使用启发式方法识别，可能不如SDK准确
  isRingDevice(device, deviceName) {
    // 方法1: 通过设备名称判断（如果名称包含Ring、Chiplet等关键字）
    if (deviceName && deviceName.trim() !== '') {
      const nameLower = deviceName.toLowerCase();
      // 常见的戒指设备名称模式
      if (nameLower.includes('ring') || 
          nameLower.includes('chiplet') || 
          nameLower.includes('chipletring') ||
          nameLower.includes('chiplet ring') ||
          nameLower.startsWith('lm') ||
          nameLower.startsWith('lomo')) {
        return true;
      }
    }

    // 方法2: 通过服务UUID判断（如果SDK定义了特定UUID）
    if (device.advertisServiceUUIDs && device.advertisServiceUUIDs.length > 0) {
      // 可以根据实际设备的UUID来判断
      // 这里列出所有发现的UUID，方便后续根据实际设备文档添加过滤
      console.log('设备UUID:', device.advertisServiceUUIDs);
      // TODO: 根据实际设备的UUID列表来判断
      // 例如：if (device.advertisServiceUUIDs.includes('0000xxxx-0000-1000-8000-00805f9b34fb')) return true;
    }

    // 方法3: 通过广播数据内容判断
    if (device.advertisData) {
      // 从广播数据中解析设备名称
      const parsedName = this.parseDeviceNameFromAdvertisData(device.advertisData);
      if (parsedName && parsedName.trim() !== '') {
        const nameLower = parsedName.toLowerCase();
        if (nameLower.includes('ring') || 
            nameLower.includes('chiplet') || 
            nameLower.includes('chipletring') ||
            nameLower.startsWith('lm') ||
            nameLower.startsWith('lomo')) {
          return true;
        }
      }
      
      // 可以解析Manufacturer Specific Data (0xFF) 来判断
      // TODO: 根据实际设备的Manufacturer ID来判断
    }

    // 由于无法使用SDK验证，暂时显示所有有名称或有广播数据的设备
    // 让用户选择连接，连接后可以根据服务UUID进一步验证
    // Android代码中要求device.getName()不为空，所以我们也要求有名称（从device.name或广播数据中解析）
    return deviceName && deviceName.trim() !== '' || (device.advertisData && device.advertisData.byteLength > 0);
  }

  // 停止扫描
  stopScan() {
    if (!this.isScanning) {
      console.log('[BLE] 当前未在扫描，无需停止');
      return;
    }

    console.log('[BLE] 停止设备扫描...');
    wx.stopBluetoothDevicesDiscovery({
      success: (res) => {
        console.log('[BLE] 停止扫描成功', res);
        this.isScanning = false;
      },
      fail: (err) => {
        console.error('[BLE] 停止扫描失败', err);
        this.isScanning = false; // 即使失败也重置状态
      },
    });
  }

  // 连接设备
  async connectDevice(deviceId, deviceName) {
    if (this.isConnected && this.deviceId === deviceId) {
      console.log('设备已连接');
      return Promise.resolve();
    }

    if (this.isScanning) {
      this.stopScan();
    }

    return new Promise((resolve, reject) => {
      wx.createBLEConnection({
        deviceId,
        success: (res) => {
          console.log('设备连接成功', res);
          this.isConnected = true;
          this.deviceId = deviceId;
          this.deviceName = deviceName;
          this.connectedAt = Date.now();
          console.log('[BLE] connectedAt 已设置', this.connectedAt);
          this._ensureValueChangeListener(); // 尽早注册，确保在任何 Notify 之前
          this.discoverServices()
            .then(() => {
              if (this.onConnectedCallback) {
                this.onConnectedCallback({
                  deviceId,
                  name: deviceName,
                });
              }
              resolve(res);
            })
            .catch((err) => {
              console.error('发现服务失败', err);
              reject(err);
            });
        },
        fail: (err) => {
          console.error('设备连接失败', err);
          this.isConnected = false;
          this.deviceId = null;
          wx.showToast({
            title: '连接失败',
            icon: 'none',
          });
          reject(err);
        },
        complete: () => {
          // 监听连接断开
          wx.onBLEConnectionStateChange((res) => {
            console.log('连接状态变化', res);
            if (!res.connected) {
              this.handleDisconnect();
            }
          });
        },
      });
    });
  }

  // 发现服务
  async discoverServices() {
    return new Promise((resolve, reject) => {
      wx.getBLEDeviceServices({
        deviceId: this.deviceId,
        success: (res) => {
          console.log('获取服务列表成功', res);
          this.services = res.services || [];
          // 查找所有特征值
          this.discoverCharacteristics()
            .then(resolve)
            .catch(reject);
        },
        fail: (err) => {
          console.error('获取服务列表失败', err);
          reject(err);
        },
      });
    });
  }

  // 发现特征值
  async discoverCharacteristics() {
    const promises = this.services.map((service) => {
      return new Promise((resolve, reject) => {
        wx.getBLEDeviceCharacteristics({
          deviceId: this.deviceId,
          serviceId: service.uuid,
          success: (res) => {
            console.log(`获取特征值成功 [${service.uuid}]`, res);
            if (!this.characteristics[service.uuid]) {
              this.characteristics[service.uuid] = [];
            }
            this.characteristics[service.uuid] = res.characteristics || [];
            if ((service.uuid || '').toLowerCase().indexOf('bae80001') >= 0) {
              (res.characteristics || []).forEach((c, i) => console.log('[BLE] BAE80001 特征[' + i + '] uuid=', c.uuid, 'properties=', JSON.stringify(c.properties || {})));
            }
            resolve(res);
          },
          fail: (err) => {
            console.error(`获取特征值失败 [${service.uuid}]`, err);
            reject(err);
          },
        });
      });
    });

    const results = await Promise.all(promises);
    // 不再调用 setBLEMTU：真机实测部分机型调用后会导致连接立即断开（如 errno 1500104），影响首次连接
    // 需要时可在此处按需开启：wx.setBLEMTU({ deviceId, mtu: 512, ... });
    console.log('[BLE] 发现特征完成，当前 deviceId=', (this.deviceId || '').slice(0, 17), 'BAE80001 特征数=', (this.characteristics && Object.keys(this.characteristics).filter(k => (k || '').toLowerCase().indexOf('bae80001') >= 0).length) || 0);
    return results;
  }

  /**
   * 若设备有 0x1812 心率服务且含 0x2A37(Heart Rate Measurement) 且支持 notify，则订阅之。
   * 用于诊断：若 0x2A37 能触发 onBLECharacteristicValueChange，说明本机 Notify 机制正常，问题或在 BAE80011/固件。
   */
  async enableHrmNotifyIfAvailable() {
    this._ensureValueChangeListener();
    const svcKey = Object.keys(this.characteristics || {}).find(k => (k || '').toLowerCase().indexOf('1812') >= 0);
    if (!svcKey) {
      console.log('[BLE] 诊断：未发现 0x1812 服务，跳过 0x2A37 订阅');
      return;
    }
    const list = this.characteristics[svcKey] || [];
    console.log('[BLE] 诊断：0x1812 有', list.length, '个特征，查找 0x2A37(HRM)');
    const hrm = list.find(c => (c.uuid || '').toLowerCase().indexOf('2a37') >= 0 && (c.properties || {}).notify);
    if (!hrm) {
      console.log('[BLE] 诊断：0x1812 无 0x2A37 或不支持 notify，跳过');
      return;
    }
    const key = svcKey + hrm.uuid;
    if (this._notifyEnabled[key]) {
      console.log('[BLE] 诊断：0x2A37 已订阅过，跳过');
      return;
    }
    const s = svcKey;
    const c = hrm.uuid;
    console.log('[BLE] 诊断：订阅 0x1812 的 0x2A37(HRM) serviceId=', s.slice(0, 8), 'charId=', c.slice(0, 8));
    try {
      await new Promise((resolve, reject) => {
        wx.notifyBLECharacteristicValueChange({
          deviceId: this.deviceId,
          serviceId: s,
          characteristicId: c,
          state: true,
          type: 'notification',
          success: () => { this._notifyEnabled[key] = true; console.log('[BLE] 诊断：0x2A37 HRM 订阅成功，若收到回调说明 Notify 机制正常'); resolve(); },
          fail: (e) => { console.warn('[BLE] 诊断：0x2A37 HRM 订阅失败', e); resolve(); },
        });
      });
    } catch (e) { console.warn('[BLE] enableHrmNotifyIfAvailable 异常', e); }
  }

  // 启用特征值通知（由 _ensureValueChangeListener 统一处理）
  // 安卓：type='notification' 必须；安卓优先小写 UUID。
  // 安卓：notify 成功后与下次 write 需间隔>500ms 否则易 10008；此处 success 内延时 1000ms。
  async enableNotification(serviceId, characteristicId) {
    this._ensureValueChangeListener();
    const key = (serviceId || '') + (characteristicId || '');
    if (this._notifyEnabled[key]) {
      console.log('[BLE] enableNotification 已订阅过，跳过 charId=', (characteristicId || '').slice(0, 18));
      return Promise.resolve();
    }
    const trySubscribe = (sId, cId, typeVal, label) => {
      const opts = { deviceId: this.deviceId, serviceId: sId, characteristicId: cId, state: true, type: typeVal };
      console.log('[BLE] 即将订阅 Notify', label, JSON.stringify({ deviceId: (opts.deviceId || '').slice(0, 17), serviceId: sId, characteristicId: cId, type: opts.type }));
      return new Promise((resolve, reject) => {
        wx.notifyBLECharacteristicValueChange({
          deviceId: opts.deviceId,
          serviceId: opts.serviceId,
          characteristicId: opts.characteristicId,
          state: opts.state,
          type: opts.type,
          success: async (res) => {
            console.log('[BLE] 启用通知成功', label, 'charId=', (cId || '').slice(0, 18), res);
            this._notifyEnabled[key] = true;
            await new Promise(r => setTimeout(r, 300));
            console.log('[BLE] Notify 已开启(与下次 write 已间隔 300ms)。C6 戒指在 Notify 后若长时间不写会断开，故缩短延时');
            resolve(res);
          },
          fail: (err) => { reject(err); },
        });
      });
    };
    const s0 = (serviceId || '').trim();
    const c0 = (characteristicId || '').trim();
    let isAndroid = false;
    try { isAndroid = (wx.getSystemInfoSync().platform || '') === 'android'; } catch (e) { isAndroid = false; }
    // 安卓：部分机型需小写 UUID 才触发 onBLECharacteristicValueChange，优先小写
    const first = isAndroid ? { s: s0.toLowerCase(), c: c0.toLowerCase(), label: '(安卓优先小写UUID)' } : { s: s0, c: c0, label: '(原始UUID)' };
    const second = isAndroid ? { s: s0, c: c0, label: '(原始UUID)' } : { s: s0.toLowerCase(), c: c0.toLowerCase(), label: '(小写UUID)' };
    for (const typ of ['notification', 'indicate']) {
      try {
        return await trySubscribe(first.s, first.c, typ, first.label + ' type=' + typ);
      } catch (e1) {
        if (first.s !== second.s || first.c !== second.c) {
          try {
            return await trySubscribe(second.s, second.c, typ, second.label + ' type=' + typ);
          } catch (e2) {
            console.warn('[BLE] 启用通知 type=' + typ + ' 均失败', e1, e2);
          }
        } else {
          console.warn('[BLE] 启用通知 type=' + typ + ' 失败', e1);
        }
      }
    }
    throw new Error('enableNotification 全部尝试失败');
  }

  // 写入特征值（value 为字符串时按 charCode 转；建议用 writeCharacteristicWithBytes 写二进制）
  async writeCharacteristic(serviceId, characteristicId, value) {
    const buffer = typeof value === 'string' ? this.stringToArrayBuffer(value) : value;
    return this._doWrite(serviceId, characteristicId, buffer);
  }

  /**
   * 按字节数组写入 BLE 特征（用于戒指协议等二进制指令）
   * @param {string} serviceId
   * @param {string} characteristicId
   * @param {number[]|Uint8Array} bytes
   * @param {{ writeType?: 'writeNoResponse'|'write' }} [options] 可选；writeType 仅安卓，不传则由系统选
   */
  async writeCharacteristicWithBytes(serviceId, characteristicId, bytes, options) {
    const arr = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
    const buffer = arr.buffer;
    return this._doWrite(serviceId, characteristicId, buffer, options);
  }

  _doWrite(serviceId, characteristicId, buffer, options) {
    return new Promise((resolve, reject) => {
      if (!this.deviceId || !this.isConnected) {
        const err = { errMsg: '设备已断开，无法写入', errno: 1001 };
        console.error('[BLE] 写入特征值失败', err);
        reject(err);
        return;
      }
      const obj = {
        deviceId: this.deviceId,
        serviceId,
        characteristicId,
        value: buffer,
        success: (res) => {
          this.lastWriteAt = Date.now();
          console.log('[BLE] 写入特征值成功 lastWriteAt=', this.lastWriteAt, options && options.writeType ? 'writeType=' + options.writeType : '', res);
          resolve(res);
        },
        fail: (err) => {
          console.error('[BLE] 写入特征值失败', err);
          reject(err);
        },
      };
      if (options && (options.writeType === 'writeNoResponse' || options.writeType === 'write')) {
        obj.writeType = options.writeType; // 仅安卓，基础库 2.9.0+
        console.log('[BLE] 本次写入 writeType=', options.writeType, 'charId=', (characteristicId || '').slice(0, 18));
      }
      wx.writeBLECharacteristicValue(obj);
    });
  }

  // 读取特征值
  async readCharacteristic(serviceId, characteristicId) {
    return new Promise((resolve, reject) => {
      wx.readBLECharacteristicValue({
        deviceId: this.deviceId,
        serviceId,
        characteristicId,
        success: (res) => {
          console.log('读取特征值成功', res);
          resolve(res);
        },
        fail: (err) => {
          console.error('读取特征值失败', err);
          reject(err);
        },
      });
    });
  }

  // 断开连接
  async disconnect() {
    if (!this.isConnected || !this.deviceId) {
      return Promise.resolve();
    }

    return new Promise((resolve, reject) => {
      wx.closeBLEConnection({
        deviceId: this.deviceId,
        success: (res) => {
          console.log('断开连接成功', res);
          this.handleDisconnect();
          resolve(res);
        },
        fail: (err) => {
          console.error('断开连接失败', err);
          this.handleDisconnect();
          reject(err);
        },
      });
    });
  }

  // 处理断开连接
  handleDisconnect() {
    this.isConnected = false;
    const deviceId = this.deviceId;
    const deviceName = this.deviceName;
    this.deviceId = null;
    this.deviceName = null;
    this.deviceMac = null;
    this.services = [];
    this.characteristics = {};
    this._notifyEnabled = {};
    this.connectedAt = null;
    this.lastWriteAt = 0;

    try {
      const h = require('./healthDataService.js').default;
      if (h._lastAppConnectDeviceId != null || h._lastAppBindDeviceId != null || h._lastAppRefreshDeviceId != null) {
        h._lastAppConnectDeviceId = null;
        h._lastAppBindDeviceId = null;
        h._lastAppRefreshDeviceId = null;
        console.log('[HealthData] 已断开，清除 APP_BIND/APP_CONNECT/APP_REFRESH 状态');
      }
    } catch (e) {}

    if (this.onDisconnectedCallback) {
      this.onDisconnectedCallback({
        deviceId,
        name: deviceName,
      });
    }
  }

  // 关闭蓝牙适配器
  async closeAdapter() {
    if (this.isScanning) {
      this.stopScan();
    }

    if (this.isConnected) {
      await this.disconnect();
    }

    return new Promise((resolve, reject) => {
      wx.closeBluetoothAdapter({
        success: (res) => {
          console.log('关闭蓝牙适配器成功', res);
          this.adapterState = false;
          resolve(res);
        },
        fail: (err) => {
          console.error('关闭蓝牙适配器失败', err);
          reject(err);
        },
      });
    });
  }

  // 字符串转ArrayBuffer
  stringToArrayBuffer(str) {
    if (typeof str === 'string') {
      const buf = new ArrayBuffer(str.length);
      const bufView = new Uint8Array(buf);
      for (let i = 0; i < str.length; i++) {
        bufView[i] = str.charCodeAt(i);
      }
      return buf;
    }
    return str;
  }

  // ArrayBuffer转字符串
  arrayBufferToString(buffer) {
    const bytes = new Uint8Array(buffer);
    let str = '';
    for (let i = 0; i < bytes.length; i++) {
      str += String.fromCharCode(bytes[i]);
    }
    return str;
  }

  // ArrayBuffer转16进制字符串
  arrayBufferToHex(buffer) {
    const bytes = new Uint8Array(buffer);
    let hex = '';
    for (let i = 0; i < bytes.length; i++) {
      const h = bytes[i].toString(16);
      hex += (h.length === 1 ? '0' : '') + h;
    }
    return hex.toUpperCase();
  }

  // 设置回调函数
  setOnDeviceFound(callback) {
    this.onDeviceFoundCallback = callback;
  }

  setOnConnected(callback) {
    this.onConnectedCallback = callback;
  }

  setOnDisconnected(callback) {
    this.onDisconnectedCallback = callback;
  }

  setOnCharacteristicValueChange(callback) {
    this.onCharacteristicValueChangeCallback = callback;
  }

  // 获取连接状态（含 connectedAt、lastWriteAt 供业务层做连接后延时与指令间隔）
  getConnectionState() {
    return {
      isConnected: this.isConnected,
      deviceId: this.deviceId,
      deviceName: this.deviceName,
      connectedAt: this.connectedAt,
      lastWriteAt: this.lastWriteAt,
    };
  }
}

// 导出单例
const bluetoothService = new BluetoothService();
export default bluetoothService;
