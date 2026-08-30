package com.jukeraft.client.music.direct.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

final class BrowserCookieReader {
    private static final Logger LOGGER = LoggerFactory.getLogger("jukeraft-ytdirect-auth");

    record Found(YtAuthSession.Source source, Map<String, String> cookies) {
    }

    private BrowserCookieReader() {
    }

    static Found autoDetect() {
        Found firefox = tryRead(YtAuthSession.Source.FIREFOX, FirefoxCookieReader::read);
        if (firefox != null) {
            return firefox;
        }
        Found chrome = tryRead(YtAuthSession.Source.CHROME, () -> ChromiumCookieReader.read("Chrome"));
        if (chrome != null) {
            return chrome;
        }
        return tryRead(YtAuthSession.Source.EDGE, () -> ChromiumCookieReader.read("Edge"));
    }

    private interface Reader {
        Map<String, String> read() throws Exception;
    }

    private static Found tryRead(YtAuthSession.Source source, Reader reader) {
        try {
            Map<String, String> cookies = reader.read();
            if (cookies != null && (cookies.containsKey("SAPISID") || cookies.containsKey("__Secure-3PAPISID"))) {
                return new Found(source, cookies);
            }
        } catch (Exception e) {
            LOGGER.debug("No usable {} cookies ({})", source, e.toString());
        }
        return null;
    }
}
