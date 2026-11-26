package com.shunyan.prompter;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class DraftStore {
    record Draft(String id, String title, String body) {
        String label() { return title.trim().isEmpty() ? "未命名台本" : title; }
    }

    private final SharedPreferences prefs;

    DraftStore(Context context) {
        prefs = context.getSharedPreferences("manuscripts", Context.MODE_PRIVATE);
        if (!prefs.contains("drafts")) {
            Draft demo = new Draft("welcome", "第一次使用顺言",
                    "今天我们测试悬浮提词器。请跟着文字慢慢朗读。\n\n"
                    + "打开悬浮窗后，即可切换到相机或其他应用。拖动标题栏可以移动窗口，"
                    + "拖动右下角可以调节大小。\n\n"
                    + "匀速模式按设定速度滚动。离线跟读会在你开口后推进，停下来时稿件也会等待。");
            write(java.util.Collections.singletonList(demo));
        }
    }

    List<Draft> all() {
        ArrayList<Draft> drafts = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString("drafts", "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                drafts.add(new Draft(item.getString("id"), item.getString("title"), item.getString("body")));
            }
        } catch (JSONException e) {
            android.util.Log.e("DraftStore", "Could not read manuscript storage", e);
        }
        return drafts;
    }

    Draft find(String id) {
        for (Draft draft : all()) if (draft.id().equals(id)) return draft;
        return null;
    }

    Draft blank() {
        return new Draft(UUID.randomUUID().toString(), "", "");
    }

    void put(Draft draft) {
        Draft existing = find(draft.id());
        var drafts = all();
        drafts.removeIf(item -> item.id().equals(draft.id()));
        drafts.add(0, draft);
        write(drafts);
        if (existing != null && !existing.body().equals(draft.body())) {
            prefs.edit().remove(positionKey(draft.id(), true))
                    .remove(positionKey(draft.id(), false)).apply();
        }
    }

    void delete(String id) {
        var drafts = all();
        drafts.removeIf(item -> item.id().equals(id));
        write(drafts);
        prefs.edit().remove(positionKey(id, true)).remove(positionKey(id, false)).apply();
    }

    int position(String id, boolean voice) {
        return prefs.getInt(positionKey(id, voice), 0);
    }

    void savePosition(String id, boolean voice, int position) {
        prefs.edit().putInt(positionKey(id, voice), position).apply();
    }

    private String positionKey(String id, boolean voice) {
        return "cursor." + id + (voice ? ".voice" : ".speed");
    }

    private void write(List<Draft> drafts) {
        JSONArray array = new JSONArray();
        for (Draft draft : drafts) {
            try {
                array.put(new JSONObject().put("id", draft.id()).put("title", draft.title()).put("body", draft.body()));
            } catch (JSONException e) {
                throw new IllegalStateException(e);
            }
        }
        prefs.edit().putString("drafts", array.toString()).apply();
    }
}
