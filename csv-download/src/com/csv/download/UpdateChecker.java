package com.csv.download;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Consulta la ultima release publicada en GitHub (API publica, sin token).
 * Java puro. Si no hay releases (404, tambien si el repositorio es
 * privado) devuelve null.
 */
public final class UpdateChecker {

    /** Mantener igual a Bundle-Version de META-INF/MANIFEST.MF. */
    public static final String CURRENT_VERSION = "1.1.0";
    public static final String REPO = "miguelacg74/csv-download";
    public static final String REPO_URL = "https://github.com/" + REPO;
    public static final String RELEASES_URL = REPO_URL + "/releases";
    public static final String JAR_NAME = "com.csv.download.jar";
    private static final String API_LATEST =
            "https://api.github.com/repos/" + REPO + "/releases/latest";

    /** Datos de una release. jarUrl puede ser null si no hay JAR adjunto. */
    public static final class Release {
        public final String version;
        public final String pageUrl;
        public final String jarUrl;

        Release(String version, String pageUrl, String jarUrl) {
            this.version = version;
            this.pageUrl = pageUrl;
            this.jarUrl = jarUrl;
        }
    }

    private UpdateChecker() {
    }

    /** Ultima release o null si el repositorio aun no publico ninguna. */
    public static Release fetchLatest() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API_LATEST)
                .openConnection();
        try {
            c.setConnectTimeout(5000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "csv-download");
            int code = c.getResponseCode();
            if (code == 404) {
                return null;
            }
            if (code == 403 || code == 429) {
                throw new IllegalStateException("GitHub limita las consultas"
                        + " anonimas (HTTP " + code + "); prueba mas tarde");
            }
            if (code != 200) {
                throw new IllegalStateException("GitHub respondio HTTP " + code);
            }
            InputStream in = c.getInputStream();
            try {
                return parseRelease(readAll(in));
            } finally {
                in.close();
            }
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) >= 0) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Lee la respuesta JSON de /releases/latest. */
    public static Release parseRelease(String json) {
        Map<String, String> m = JsonUtil.parseObjectRaw(json);
        String tag = m.get("tag_name") == null ? "" : m.get("tag_name");
        String page = m.get("html_url") == null ? RELEASES_URL
                : m.get("html_url");
        String jar = null;
        String fallback = null;
        List<Map<String, String>> assets = JsonUtil.parseArray(m.get("assets"));
        for (Map<String, String> a : assets) {
            String name = a.get("name");
            String url = a.get("browser_download_url");
            if (name == null || url == null || !name.endsWith(".jar")) {
                continue;
            }
            if (JAR_NAME.equals(name)) {
                jar = url;
                break;
            }
            if (fallback == null) {
                fallback = url;
            }
        }
        return new Release(tag.replaceFirst("^[vV]", ""), page,
                jar != null ? jar : fallback);
    }

    /** Compara versiones numericas "1.6.0" vs "1.10": negativo, 0 o positivo. */
    public static int compare(String a, String b) {
        int[] x = parts(a);
        int[] y = parts(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0;
            int q = i < y.length ? y[i] : 0;
            if (p != q) {
                return Integer.compare(p, q);
            }
        }
        return 0;
    }

    private static int[] parts(String v) {
        Matcher m = Pattern.compile("\\d+").matcher(v == null ? "" : v);
        int[] r = new int[8];
        int n = 0;
        while (m.find() && n < r.length) {
            r[n++] = Integer.parseInt(m.group());
        }
        return Arrays.copyOf(r, n);
    }
}
