package com.jukeraft.client.music;

public enum Provider {
    YTM(38214, "YTM Music", "logo-ytmusic",
            0xEB241A36, 0xEB120F1A,
            0xFF2A2438,
            0xFFB0A8C0,
            0xFFE8E2F2,
            0xFFC9ADFF,
            0xFF8A5CF0, 0xFF5B21B6,
            0xFFFFFFFF,
            0xFFD8C9FF),
    SPOTIFY(38215, "Spotify", "logo-spotify",
            0xEB18181A, 0xEB0C0C0E,
            0xFF282828,
            0xFFA7A7A7,
            0xFFE8E8E8,
            0xFF1DB954,
            0xFF1DB954, 0xFF1DB954,
            0xFF000000,
            0xFF1ED760);

    public final int port;
    public final String displayName;
    public final String logoIcon;
    public final int cardTop;
    public final int cardBottom;
    public final int thumbBg;
    public final int secondary;
    public final int icon;
    public final int iconActive;
    public final int playTop;
    public final int playBottom;
    public final int playIcon;
    public final int sliderThumb;

    Provider(int port, String displayName, String logoIcon, int cardTop, int cardBottom, int thumbBg, int secondary,
             int icon, int iconActive, int playTop, int playBottom, int playIcon, int sliderThumb) {
        this.port = port;
        this.displayName = displayName;
        this.logoIcon = logoIcon;
        this.cardTop = cardTop;
        this.cardBottom = cardBottom;
        this.thumbBg = thumbBg;
        this.secondary = secondary;
        this.icon = icon;
        this.iconActive = iconActive;
        this.playTop = playTop;
        this.playBottom = playBottom;
        this.playIcon = playIcon;
        this.sliderThumb = sliderThumb;
    }

    public Provider other() {
        return this == YTM ? SPOTIFY : YTM;
    }

    public static Provider fromName(String name, Provider fallback) {
        if (name == null) {
            return fallback;
        }
        for (Provider p : values()) {
            if (p.name().equalsIgnoreCase(name)) {
                return p;
            }
        }
        return fallback;
    }
}
