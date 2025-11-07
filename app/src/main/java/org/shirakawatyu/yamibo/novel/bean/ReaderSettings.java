package org.shirakawatyu.yamibo.novel.bean;

public class ReaderSettings {
    private Float fontSizePx;
    private Float lineHeightPx;
    private Float paddingDp;// 改为了Float

    private Boolean nightMode;

    public ReaderSettings(Float fontSizePx, Float lineHeightPx, Float padding, Boolean nightMode) { // <-- MODIFY THIS
        this.fontSizePx = fontSizePx;
        this.lineHeightPx = lineHeightPx;
        this.paddingDp = padding;
        this.nightMode = nightMode;
    }

    public ReaderSettings() {
    }

    public Float getFontSizePx() {
        return fontSizePx;
    }

    public void setFontSizePx(Float fontSizePx) {
        this.fontSizePx = fontSizePx;
    }

    public Float getLineHeightPx() {
        return lineHeightPx;
    }

    public void setLineHeightPx(Float lineHeightPx) {
        this.lineHeightPx = lineHeightPx;
    }

    public Float getPaddingDp() {
        return paddingDp;
    }

    public void setPaddingDp(Float paddingDp) {
        this.paddingDp = paddingDp;
    }

    public Boolean getNightMode() {
        return nightMode;
    }

    public void setNightMode(Boolean nightMode) {
        this.nightMode = nightMode;
    }
}
