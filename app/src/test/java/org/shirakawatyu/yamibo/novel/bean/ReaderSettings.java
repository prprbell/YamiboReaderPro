package org.shirakawatyu.yamibo.novel.bean;

public class ReaderSettings {
    private Float fontSizePx;
    private Float lineHeightPx;
    // Dp padding 修改为了 Float padding，因为fastjson无法正常处理Dp类型
    private Float padding;

    public ReaderSettings(Float fontSizePx, Float lineHeightPx, Float padding) {
        this.fontSizePx = fontSizePx;
        this.lineHeightPx = lineHeightPx;
        this.padding = padding;
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

    public Float getPadding() {
        return padding;
    }

    public void setPadding(Float padding) {
        this.padding = padding;
    }
}
