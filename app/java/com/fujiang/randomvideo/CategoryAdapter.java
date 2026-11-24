package com.fujiang.randomvideo;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

public class CategoryAdapter extends BaseAdapter {
    public interface OnPick {
        void onPick(Category category);
    }

    private final Context ctx;
    private final Library lib;
    private final OnPick pick;

    public CategoryAdapter(Context context, Library library, OnPick onPick) {
        this.ctx = context;
        this.lib = library;
        this.pick = onPick;
    }

    @Override
    public int getCount() {
        return this.lib.categories.size();
    }

    @Override
    public Object getItem(int position) {
        return this.lib.categories.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(int position, View view, ViewGroup parent) {
        if (view == null) {
            view = LayoutInflater.from(this.ctx).inflate(R.layout.item_category, parent, false);
        }
        final Category category = this.lib.categories.get(position);
        TextView name = (TextView) view.findViewById(R.id.tv_cat_name);
        TextView sub = (TextView) view.findViewById(R.id.tv_cat_sub);
        TextView count = (TextView) view.findViewById(R.id.tv_cat_count);

        name.setText(category.name);
        sub.setText(category.sub);
        if (category.isApi()) {
            count.setText("● 在线");
        } else {
            count.setText(this.lib.count(category.key) + " 部");
        }

        // 同一个分类不再重复 new GradientDrawable，滚动时少一点 GC 抖动
        Object tag = view.getTag();
        if (!category.key.equals(tag)) {
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{category.colorStart, category.colorEnd});
            bg.setCornerRadius(Util.dp(this.ctx, 18f));
            bg.setStroke(Util.dp(this.ctx, 1f), 0x33ff9ad5);
            view.setBackground(bg);
            view.setTag(category.key);
        }
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (pick != null) {
                    pick.onPick(category);
                }
            }
        });
        return view;
    }
}