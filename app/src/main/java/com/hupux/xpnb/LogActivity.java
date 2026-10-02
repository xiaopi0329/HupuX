package com.hupux.xpnb;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 拦截日志界面。
 *
 * <p>显示虎扑进程里的模块写过来的记录（经 {@link LogProvider} 落盘）。
 * 每行格式：{@code 时间戳|时间|功能|目标|结果}。</p>
 */
public class LogActivity extends AppCompatActivity {

    private ListView listView;
    private TextView emptyView;
    private TextView summaryView;
    private LogAdapter adapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        listView = findViewById(R.id.lv_log);
        emptyView = findViewById(R.id.tv_log_empty);
        summaryView = findViewById(R.id.tv_log_summary);

        adapter = new LogAdapter();
        listView.setAdapter(adapter);

        Button clear = findViewById(R.id.btn_clear);
        clear.setOnClickListener(v -> {
            AdsLogStore.clear(this);
            reload();
        });

        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        List<String> raw = AdsLogStore.readAll(this);
        Collections.reverse(raw);   // 最新的排在最前
        adapter.setData(raw);

        int blocked = 0;
        for (String line : raw) {
            if (line.endsWith("已拦截")) {
                blocked++;
            }
        }
        summaryView.setText(getString(R.string.log_summary, raw.size(), blocked));
        emptyView.setVisibility(raw.isEmpty() ? View.VISIBLE : View.GONE);
    }

    /** 一行记录拆成两行显示。 */
    private static final class Entry {
        final String time;
        final String feature;
        final String detail;
        final String result;

        Entry(String raw) {
            String[] parts = raw.split("\\|", -1);
            time = parts.length > 1 ? parts[1] : "";
            feature = parts.length > 2 ? parts[2] : "";
            detail = parts.length > 3 ? parts[3] : raw;
            result = parts.length > 4 ? parts[4] : "";
        }
    }

    private static final class LogAdapter extends BaseAdapter {

        private final List<Entry> data = new ArrayList<>();

        void setData(List<String> raw) {
            data.clear();
            for (String line : raw) {
                data.add(new Entry(line));
            }
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return data.size();
        }

        @Override
        public Object getItem(int position) {
            return data.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_log, parent, false);
            }
            Entry e = data.get(position);
            TextView line1 = view.findViewById(R.id.tv_log_line1);
            TextView line2 = view.findViewById(R.id.tv_log_line2);
            line1.setText(e.feature.isEmpty()
                    ? e.time
                    : e.time + "   [" + e.feature + "]");
            line2.setText(e.result.isEmpty() ? e.detail : e.detail + "  →  " + e.result);
            return view;
        }
    }
}
