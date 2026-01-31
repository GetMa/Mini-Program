package com.lomo.demo.activity;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.lm.sdk.mode.HistoryDataBean;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class HistoryDataManager {
    private static final String TAG = "HistoryDataManager";
    private static final String PREFS_NAME = "history_data_prefs";
    private static final String KEY_HISTORY_DATA = "history_data_list";
    private static final int MAX_DATA_COUNT = 10000; // 最多保存10000条记录

    /**
     * 保存历史数据
     */
    public static void saveHistoryData(Context context, HistoryDataBean data) {
        if (data == null) {
            return;
        }

        try {
            List<HistoryDataBean> existingData = loadHistoryData(context);
            if (existingData == null) {
                existingData = new ArrayList<>();
            }

            // 添加新数据
            existingData.add(data);

            // 如果数据过多，删除最旧的数据
            if (existingData.size() > MAX_DATA_COUNT) {
                existingData = existingData.subList(existingData.size() - MAX_DATA_COUNT, existingData.size());
            }

            // 保存到SharedPreferences
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            Gson gson = new Gson();
            String json = gson.toJson(existingData);
            prefs.edit().putString(KEY_HISTORY_DATA, json).apply();

            Log.d(TAG, "保存历史数据成功，当前总数: " + existingData.size());
        } catch (Exception e) {
            Log.e(TAG, "保存历史数据失败", e);
        }
    }

    /**
     * 批量保存历史数据
     */
    public static void saveHistoryDataList(Context context, List<HistoryDataBean> dataList) {
        if (dataList == null || dataList.isEmpty()) {
            return;
        }

        try {
            List<HistoryDataBean> existingData = loadHistoryData(context);
            if (existingData == null) {
                existingData = new ArrayList<>();
            }

            // 添加新数据
            existingData.addAll(dataList);

            // 如果数据过多，删除最旧的数据
            if (existingData.size() > MAX_DATA_COUNT) {
                existingData = existingData.subList(existingData.size() - MAX_DATA_COUNT, existingData.size());
            }

            // 保存到SharedPreferences
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            Gson gson = new Gson();
            String json = gson.toJson(existingData);
            prefs.edit().putString(KEY_HISTORY_DATA, json).apply();

            Log.d(TAG, "批量保存历史数据成功，当前总数: " + existingData.size());
        } catch (Exception e) {
            Log.e(TAG, "批量保存历史数据失败", e);
        }
    }

    /**
     * 加载历史数据
     */
    public static List<HistoryDataBean> loadHistoryData(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String json = prefs.getString(KEY_HISTORY_DATA, null);
            
            if (json == null || json.isEmpty()) {
                return new ArrayList<>();
            }

            Gson gson = new Gson();
            Type type = new TypeToken<List<HistoryDataBean>>(){}.getType();
            List<HistoryDataBean> dataList = gson.fromJson(json, type);
            
            if (dataList == null) {
                return new ArrayList<>();
            }

            Log.d(TAG, "加载历史数据成功，总数: " + dataList.size());
            return dataList;
        } catch (Exception e) {
            Log.e(TAG, "加载历史数据失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 清空历史数据
     */
    public static void clearHistoryData(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().remove(KEY_HISTORY_DATA).apply();
            Log.d(TAG, "清空历史数据成功");
        } catch (Exception e) {
            Log.e(TAG, "清空历史数据失败", e);
        }
    }
}

