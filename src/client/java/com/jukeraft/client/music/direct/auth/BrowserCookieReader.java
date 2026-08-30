package com.jukeraft.client.music.direct.auth;

import java.util.Map;

final class BrowserCookieReader {
    record Found(YtAuthSession.Source source, Map<String, String> cookies) {
    }

    private BrowserCookieReader() {
    }

    static Found autoDetect() {
        return null;
    }
}
