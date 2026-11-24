package com.fujiang.randomvideo;

public class Video {
    public String category;
    public long durationMs;
    public boolean local;
    public String sourceLabel;
    /** 源站给的封面图地址（可能为空）。 */
    public String cover;
    public String title;
    public String url;

    public Video(String str, String str2, String str3, String str4, long j, boolean z) {
        this.title = Util.safe(str);
        this.url = Util.safe(str2);
        this.category = Util.safe(str3);
        this.sourceLabel = Util.safe(str4);
        this.cover = "";
        this.durationMs = j;
        this.local = z;
    }

    public String key() {
        return this.url;
    }

    public String metaText() {
        StringBuilder sb = new StringBuilder();
        sb.append(this.sourceLabel.length() > 0 ? this.sourceLabel : (this.local ? "本地" : "在线"));
        if (this.durationMs > 0) {
            sb.append(" · ");
            sb.append(Util.fmtTime((int) this.durationMs));
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Video)) {
            return false;
        }
        return this.url.equals(((Video) o).url);
    }

    @Override
    public int hashCode() {
        return this.url.hashCode();
    }
}