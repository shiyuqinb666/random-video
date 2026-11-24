package com.fujiang.randomvideo;

public class Category {
    public final String[] apis;
    public final int colorEnd;
    public final int colorStart;
    public final String key;
    public final String[] keywords;
    public final String name;
    public final boolean online;
    public final String sub;

    public Category(String str, String str2, String str3, int i, int i2, String... strArr) {
        this.key = str;
        this.name = str2;
        this.sub = str3;
        this.colorStart = i;
        this.colorEnd = i2;
        this.apis = new String[0];
        this.online = false;
        this.keywords = strArr == null ? new String[0] : strArr;
    }

    public Category(String str, String str2, String str3, int i, int i2, boolean z, String[] strArr, String... strArr2) {
        this.key = str;
        this.name = str2;
        this.sub = str3;
        this.colorStart = i;
        this.colorEnd = i2;
        String[] apis = strArr == null ? new String[0] : strArr;
        this.apis = apis;
        this.online = z && apis.length > 0;
        this.keywords = strArr2 == null ? new String[0] : strArr2;
    }

    public boolean isSpecial() {
        return Library.C_ALL.equals(this.key) || Library.C_FAV.equals(this.key);
    }

    public boolean isApi() {
        return this.apis != null && this.apis.length > 0;
    }
}