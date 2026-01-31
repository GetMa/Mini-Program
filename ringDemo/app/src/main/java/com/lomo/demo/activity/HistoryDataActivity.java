package com.lomo.demo.activity;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hjq.permissions.OnPermissionCallback;
import com.hjq.permissions.Permission;
import com.hjq.permissions.XXPermissions;
import com.lm.sdk.library.utils.DateUtils;
import com.lm.sdk.mode.HistoryDataBean;
import com.lomo.demo.R;
import com.lomo.demo.adapter.HistoryDataAdapter;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class HistoryDataActivity extends AppCompatActivity {
    private RecyclerView recyclerView;
    private HistoryDataAdapter adapter;
    private Button btnExport;
    private TextView tvEmpty;
    private List<HistoryDataBean> historyDataList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history_data);

        initView();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadHistoryData();
    }

    private void initView() {
        recyclerView = findViewById(R.id.recyclerView);
        btnExport = findViewById(R.id.btn_export);
        tvEmpty = findViewById(R.id.tv_empty);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        adapter = new HistoryDataAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        btnExport.setOnClickListener(v -> exportHistoryData());
    }

    private void loadHistoryData() {
        try {
            // 从本地SharedPreferences或文件读取保存的历史数据
            historyDataList = HistoryDataManager.loadHistoryData(this);
            
            if (historyDataList != null && !historyDataList.isEmpty()) {
                adapter.setData(historyDataList);
                tvEmpty.setVisibility(View.GONE);
                recyclerView.setVisibility(View.VISIBLE);
            } else {
                tvEmpty.setVisibility(View.VISIBLE);
                recyclerView.setVisibility(View.GONE);
                Toast.makeText(this, "暂无历史数据，请先通过设备读取历史记录", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            e.printStackTrace();
            tvEmpty.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
            Toast.makeText(this, "加载历史数据失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void exportHistoryData() {
        if (historyDataList == null || historyDataList.isEmpty()) {
            Toast.makeText(this, "没有可导出的数据", Toast.LENGTH_SHORT).show();
            return;
        }

        // 检查并请求存储权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            XXPermissions.with(this)
                    .permission(Permission.MANAGE_EXTERNAL_STORAGE)
                    .request(new OnPermissionCallback() {
                        @Override
                        public void onGranted(@NonNull List<String> permissions, boolean allGranted) {
                            if (allGranted) {
                                performExport();
                            }
                        }

                        @Override
                        public void onDenied(@NonNull List<String> permissions, boolean doNotAskAgain) {
                            Toast.makeText(HistoryDataActivity.this, "需要存储权限才能导出数据", Toast.LENGTH_SHORT).show();
                        }
                    });
        } else {
            XXPermissions.with(this)
                    .permission(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE)
                    .request(new OnPermissionCallback() {
                        @Override
                        public void onGranted(@NonNull List<String> permissions, boolean allGranted) {
                            if (allGranted) {
                                performExport();
                            }
                        }

                        @Override
                        public void onDenied(@NonNull List<String> permissions, boolean doNotAskAgain) {
                            Toast.makeText(HistoryDataActivity.this, "需要存储权限才能导出数据", Toast.LENGTH_SHORT).show();
                        }
                    });
        }
    }

    private void performExport() {
        try {
            // 创建导出目录
            File exportDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "RingTestData");
            if (!exportDir.exists()) {
                exportDir.mkdirs();
            }

            // 生成文件名
            String fileName = "历史检测数据_" + DateUtils.longToString(System.currentTimeMillis(), "yyyyMMdd_HHmmss") + ".csv";
            File csvFile = new File(exportDir, fileName);

            // 写入CSV文件
            FileWriter writer = new FileWriter(csvFile);
            
            // 写入表头
            writer.append("时间,心率,血氧,温度,步数,卡路里,距离\n");

            // 写入数据
            for (HistoryDataBean data : historyDataList) {
                writer.append(DateUtils.longToString(data.getTime() * 1000, "yyyy-MM-dd HH:mm:ss")).append(",");
                
                // 使用反射安全获取字段值
                String heartRate = getFieldValue(data, "getHeartRate");
                String bloodOxygen = getFieldValue(data, "getBloodOxygen");
                String temperature = data.getTemperatureData() != null ? data.getTemperatureData() : "";
                String step = getFieldValue(data, "getStep");
                String calories = getFieldValue(data, "getCalories");
                String distance = getFieldValue(data, "getDistance");
                
                writer.append(heartRate).append(",");
                writer.append(bloodOxygen).append(",");
                writer.append(temperature).append(",");
                writer.append(step).append(",");
                writer.append(calories).append(",");
                writer.append(distance).append("\n");
            }

            writer.flush();
            writer.close();

            Toast.makeText(this, "数据已导出到: " + csvFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "导出失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 使用反射安全获取字段值
     */
    private String getFieldValue(HistoryDataBean data, String methodName) {
        try {
            java.lang.reflect.Method method = data.getClass().getMethod(methodName);
            Object value = method.invoke(data);
            return value != null ? String.valueOf(value) : "";
        } catch (Exception e) {
            return "";
        }
    }
}

