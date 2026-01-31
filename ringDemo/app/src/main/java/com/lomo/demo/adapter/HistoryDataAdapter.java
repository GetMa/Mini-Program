package com.lomo.demo.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.lm.sdk.library.utils.DateUtils;
import com.lm.sdk.mode.HistoryDataBean;
import com.lomo.demo.R;

import java.util.ArrayList;
import java.util.List;

public class HistoryDataAdapter extends RecyclerView.Adapter<HistoryDataAdapter.ViewHolder> {
    private List<HistoryDataBean> dataList = new ArrayList<>();

    public void setData(List<HistoryDataBean> dataList) {
        this.dataList = dataList != null ? dataList : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_history_data, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        HistoryDataBean data = dataList.get(position);
        
        // 显示时间
        String timeStr = DateUtils.longToString(data.getTime() * 1000, "yyyy-MM-dd HH:mm:ss");
        holder.tvTime.setText("时间: " + timeStr);
        
        // 显示数据 - 使用反射或toString方法获取所有数据
        String dataStr = data.toString();
        // 如果toString()返回的是对象地址，则显示基本信息
        if (dataStr == null || dataStr.startsWith("com.lm.sdk")) {
            StringBuilder sb = new StringBuilder();
            try {
                if (data.getTemperatureData() != null && !data.getTemperatureData().isEmpty()) {
                    sb.append("温度: ").append(data.getTemperatureData()).append(" ");
                }
                // 尝试获取其他字段（如果存在）
                try {
                    java.lang.reflect.Method getHeartRate = data.getClass().getMethod("getHeartRate");
                    Object heartRate = getHeartRate.invoke(data);
                    if (heartRate != null) {
                        sb.append("心率: ").append(heartRate).append(" ");
                    }
                } catch (Exception e) {
                    // 方法不存在，忽略
                }
                try {
                    java.lang.reflect.Method getBloodOxygen = data.getClass().getMethod("getBloodOxygen");
                    Object bloodOxygen = getBloodOxygen.invoke(data);
                    if (bloodOxygen != null) {
                        sb.append("血氧: ").append(bloodOxygen).append(" ");
                    }
                } catch (Exception e) {
                    // 方法不存在，忽略
                }
                try {
                    java.lang.reflect.Method getStep = data.getClass().getMethod("getStep");
                    Object step = getStep.invoke(data);
                    if (step != null) {
                        sb.append("步数: ").append(step).append(" ");
                    }
                } catch (Exception e) {
                    // 方法不存在，忽略
                }
            } catch (Exception e) {
                // 忽略错误
            }
            dataStr = sb.length() > 0 ? sb.toString().trim() : "检测数据";
        }
        
        holder.tvData.setText(dataStr);
    }

    @Override
    public int getItemCount() {
        return dataList.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvTime;
        TextView tvData;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTime = itemView.findViewById(R.id.tv_time);
            tvData = itemView.findViewById(R.id.tv_data);
        }
    }
}

