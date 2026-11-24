package com.fujiang.randomvideo;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class VideoAdapter extends BaseAdapter {
    public interface Listener {
        void onPlay(Video video, int position);

        void onToggleFav(Video video, int position);

        void onLongPress(Video video, int position);
    }

    private static final class Holder {
        TextView title;
        TextView meta;
        ImageView fav;
    }

    private final Context ctx;
    private final Library lib;
    private final Listener listener;
    private final List<Video> data = new ArrayList<Video>();

    public VideoAdapter(Context context, Library library, Listener listener) {
        this.ctx = context;
        this.lib = library;
        this.listener = listener;
    }

    public void setData(List<Video> list) {
        this.data.clear();
        if (list != null) {
            this.data.addAll(list);
        }
        notifyDataSetChanged();
    }

    public List<Video> data() {
        return this.data;
    }

    @Override
    public int getCount() {
        return this.data.size();
    }

    @Override
    public Object getItem(int position) {
        return position >= 0 && position < this.data.size() ? this.data.get(position) : null;
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public View getView(final int position, View view, ViewGroup parent) {
        Holder holder;
        if (view == null) {
            view = LayoutInflater.from(this.ctx).inflate(R.layout.item_video, parent, false);
            holder = new Holder();
            holder.title = (TextView) view.findViewById(R.id.tv_video_title);
            holder.meta = (TextView) view.findViewById(R.id.tv_video_meta);
            holder.fav = (ImageView) view.findViewById(R.id.img_fav);
            view.setTag(holder);
        } else {
            holder = (Holder) view.getTag();
        }
        // 数据可能在绑定过程中被替换（导入/删除/搜索过滤），越界就交回空行避免崩溃
        if (position < 0 || position >= this.data.size()) {
            return view;
        }
        final Video video = this.data.get(position);
        holder.title.setText((position + 1) + ". " + Util.ellipsis(video.title, 40));
        holder.meta.setText(video.metaText());
        boolean fav = this.lib.isFav(video);
        holder.fav.setImageResource(R.drawable.ic_star);
        holder.fav.setColorFilter(fav ? 0xffff4f9a : 0x33ffffff);

        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onPlay(video, position);
                }
            }
        });
        view.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (listener != null) {
                    listener.onLongPress(video, position);
                }
                return true;
            }
        });
        holder.fav.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onToggleFav(video, position);
                }
            }
        });
        return view;
    }
}