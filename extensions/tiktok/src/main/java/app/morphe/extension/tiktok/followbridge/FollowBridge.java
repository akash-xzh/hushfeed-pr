package app.morphe.extension.tiktok.followbridge;

import android.app.Activity;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.comment.AccountCountry;

/**
 * Localhost HTTP bridge (127.0.0.1:48912) inside TikTok so Termux can trigger
 * native signed follow requests without opening TikTok's UI.
 */
public final class FollowBridge {
    private static final int[] PORTS_MAIN = {48912, 48914, 48916, 48918};
    private static final int[] PORTS_ALT = {48914, 48916, 48918, 48912};
    private static final String SERVICE_MANAGER = "com.ss.android.ugc.aweme.framework.services.ServiceManager";
    private static final String RETROFIT_SERVICE = "com.ss.android.ugc.aweme.framework.services.IRetrofitService";
    private static final String RETROFIT = "com.ss.android.ugc.aweme.framework.services.IRetrofit";
    private static final String API = "com.ss.android.ugc.aweme.app.api.Api";
    private static final String NETWORK_API = "com.bytedance.ttnet.INetworkApi";

    private static volatile boolean started = false;
    private static volatile int boundPort = 48912;
    private static volatile String appPackage = "";
    private static volatile int androidUserId = 0;
    private static volatile Object cachedNetworkApi;
    private static volatile String cachedHost;

    private FollowBridge() {}

    public static void install(Activity activity, Bundle savedInstanceState) {
        if (activity != null && appPackage.isEmpty()) {
            try { appPackage = activity.getPackageName(); } catch (Throwable ignored) {}
        }
        androidUserId = android.os.Process.myUid() / 100000;
        if (started) return;
        synchronized (FollowBridge.class) {
            if (started) return;
            started = true;
            Thread t = new Thread(FollowBridge::serveLoop, "PRFollowBridge");
            t.setDaemon(true);
            t.start();
        }
    }

    private static void serveLoop() {
        boolean isAlt = appPackage.endsWith(".go") || androidUserId != 0;
        int[] candidates = isAlt ? PORTS_ALT : PORTS_MAIN;
        ServerSocket server = null;
        for (int p : candidates) {
            try {
                server = new ServerSocket(p, 16, InetAddress.getByName("127.0.0.1"));
                boundPort = p;
                break;
            } catch (Throwable ignored) {}
        }
        if (server == null) {
            started = false;
            return;
        }
        try (ServerSocket s = server) {
            Logger.printInfo(() -> "FollowBridge listening on 127.0.0.1:" + boundPort + " (" + appPackage + ")");
            while (true) {
                Socket client = s.accept();
                Thread worker = new Thread(() -> handleClient(client), "PRFollowWorker");
                worker.setDaemon(true);
                worker.start();
            }
        } catch (Throwable t) {
            Logger.printException(() -> "FollowBridge server failed", t);
            started = false;
        }
    }

    private static void handleClient(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(15000);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String reqLine = reader.readLine();
            if (reqLine == null || reqLine.isEmpty()) return;
            // Drain headers
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // ignore
            }

            String[] parts = reqLine.split(" ");
            String target = parts.length > 1 ? parts[1] : "/";
            int qIdx = target.indexOf('?');
            String path = qIdx >= 0 ? target.substring(0, qIdx) : target;
            Map<String, String> params = parseQuery(qIdx >= 0 ? target.substring(qIdx + 1) : "");

            JSONObject respJson;
            int httpStatus = 200;
            try {
                if ("/ping".equals(path)) {
                    respJson = new JSONObject()
                            .put("ok", true)
                            .put("bridge", "tiktok-follow")
                            .put("package", appPackage)
                            .put("android_user", androidUserId)
                            .put("port", boundPort);
                } else if ("/profile".equals(path)) {
                    respJson = handleProfile(params);
                } else if ("/follow".equals(path)) {
                    respJson = handleFollow(params);
                } else {
                    httpStatus = 404;
                    respJson = new JSONObject().put("ok", false).put("error", "unknown endpoint: " + path);
                }
            } catch (Throwable ex) {
                httpStatus = 500;
                Throwable root = ex;
                while (root.getCause() != null && (root instanceof java.lang.reflect.InvocationTargetException || root.getMessage() == null)) {
                    root = root.getCause();
                }
                android.util.Log.e("FollowBridge", "Request failed: " + target, root);
                StringBuilder sb = new StringBuilder();
                StackTraceElement[] st = root.getStackTrace();
                for (int i = 0; i < Math.min(4, st.length); i++) {
                    if (i > 0) sb.append(" <- ");
                    sb.append(st[i].getClassName()).append(".").append(st[i].getMethodName()).append(":").append(st[i].getLineNumber());
                }
                respJson = new JSONObject()
                        .put("ok", false)
                        .put("error", root.getClass().getSimpleName() + ": " + String.valueOf(root.getMessage()))
                        .put("trace", sb.toString());
            }

            byte[] body = respJson.toString().getBytes(StandardCharsets.UTF_8);
            String hdr = "HTTP/1.1 " + httpStatus + " OK\r\n"
                    + "Content-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = s.getOutputStream();
            out.write(hdr.getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        } catch (Throwable ignored) {
        }
    }

    private static JSONObject handleProfile(Map<String, String> params) throws Exception {
        String uid = params.getOrDefault("uid", "");
        String secUid = params.getOrDefault("sec_uid", "");
        String username = cleanUsername(params.getOrDefault("username", ""));
        JSONObject user = fetchUser(uid, secUid, username);
        if (user == null) {
            return new JSONObject().put("ok", false).put("error", "user not found");
        }
        return new JSONObject()
                .put("ok", true)
                .put("uid", user.optString("uid", ""))
                .put("sec_uid", user.optString("sec_uid", ""))
                .put("unique_id", user.optString("unique_id", ""))
                .put("follow_status", user.optInt("follow_status", -1));
    }

    private static JSONObject handleFollow(Map<String, String> params) throws Exception {
        String uid = params.getOrDefault("uid", "");
        String secUid = params.getOrDefault("sec_uid", "");
        String username = cleanUsername(params.getOrDefault("username", ""));
        String type = params.getOrDefault("type", "1");
        int verifyDelay = 2500;
        try {
            if (params.containsKey("verify_delay")) {
                verifyDelay = Integer.parseInt(params.get("verify_delay"));
            }
        } catch (NumberFormatException ignored) {}

        if (uid.isEmpty() || secUid.isEmpty()) {
            JSONObject user = fetchUser(uid, secUid, username);
            if (user == null) {
                return new JSONObject().put("ok", false).put("error", "user not found");
            }
            if (uid.isEmpty()) uid = user.optString("uid", "");
            if (secUid.isEmpty()) secUid = user.optString("sec_uid", "");
            int curStatus = user.optInt("follow_status", -1);
            if ("1".equals(type) && (curStatus == 1 || curStatus == 2 || curStatus == 4)) {
                return new JSONObject()
                        .put("ok", true)
                        .put("verified", true)
                        .put("already", true)
                        .put("uid", uid)
                        .put("sec_uid", secUid)
                        .put("follow_status", curStatus);
            }
        }

        Map<String, String> q = new LinkedHashMap<>();
        q.put("user_id", uid);
        q.put("sec_user_id", secUid);
        q.put("type", type);
        q.put("from", "18");
        q.put("channel_id", "0");

        String rawFollow = postOrGet("/aweme/v1/commit/follow/user/", q);
        JSONObject followJson = null;
        try {
            if (rawFollow != null && !rawFollow.isEmpty()) followJson = new JSONObject(rawFollow);
        } catch (Throwable ignored) {}

        int apiStatus = followJson != null ? followJson.optInt("status_code", -1) : -1;
        if (apiStatus != 0) {
            return new JSONObject()
                    .put("ok", false)
                    .put("reverted", false)
                    .put("status_code", apiStatus)
                    .put("uid", uid)
                    .put("sec_uid", secUid)
                    .put("raw", rawFollow == null ? "" : rawFollow.substring(0, Math.min(200, rawFollow.length())));
        }

        if (verifyDelay > 0) {
            Thread.sleep(verifyDelay);
        }

        // Re-fetch profile to confirm follow did not auto-revert
        JSONObject checkUser = fetchUser(uid, secUid, username);
        int postStatus = checkUser != null ? checkUser.optInt("follow_status", -1) : -1;
        boolean wantFollow = "1".equals(type);
        boolean persisted = wantFollow
                ? (postStatus == 1 || postStatus == 2 || postStatus == 4)
                : (postStatus == 0);

        return new JSONObject()
                .put("ok", persisted)
                .put("verified", persisted)
                .put("reverted", wantFollow && !persisted)
                .put("status_code", apiStatus)
                .put("follow_status", postStatus)
                .put("uid", uid)
                .put("sec_uid", secUid);
    }

    private static JSONObject fetchUser(String uid, String secUid, String username) throws Exception {
        if (!uid.isEmpty() || !secUid.isEmpty()) {
            Map<String, String> q = new LinkedHashMap<>();
            if (!uid.isEmpty()) q.put("user_id", uid);
            if (!secUid.isEmpty()) q.put("sec_user_id", secUid);
            String body = AccountCountry.fetch("/aweme/v1/user/profile/other/", q);
            if (body != null && !body.isEmpty()) {
                JSONObject root = new JSONObject(body);
                if (root.optInt("status_code", -1) == 0 && root.has("user")) {
                    return root.optJSONObject("user");
                }
            }
        }
        if (!username.isEmpty()) {
            Map<String, String> q = new LinkedHashMap<>();
            q.put("unique_id", username);
            String body = AccountCountry.fetch("/aweme/v1/user/profile/other/", q);
            if (body != null && !body.isEmpty()) {
                JSONObject root = new JSONObject(body);
                if (root.optInt("status_code", -1) == 0 && root.has("user")) {
                    return root.optJSONObject("user");
                }
            }
            // Fallback: user search
            Map<String, String> sq = new LinkedHashMap<>();
            sq.put("keyword", username);
            sq.put("count", "5");
            sq.put("cursor", "0");
            sq.put("type", "1");
            String sBody = AccountCountry.fetch("/aweme/v1/discover/search/", sq);
            if (sBody != null && !sBody.isEmpty()) {
                JSONObject sRoot = new JSONObject(sBody);
                JSONArray list = sRoot.optJSONArray("user_list");
                if (list != null) {
                    for (int i = 0; i < list.length(); i++) {
                        JSONObject item = list.optJSONObject(i);
                        JSONObject u = item != null ? item.optJSONObject("user_info") : null;
                        if (u != null && username.equalsIgnoreCase(u.optString("unique_id", ""))) {
                            return u;
                        }
                    }
                    if (list.length() > 0) {
                        JSONObject item = list.optJSONObject(0);
                        if (item != null) return item.optJSONObject("user_info");
                    }
                }
            }
        }
        return null;
    }

    private static String postOrGet(String path, Map<String, String> query) throws Exception {
        try {
            Object api = networkApi();
            Class<?> cls = Class.forName(NETWORK_API);
            Method doPost = cls.getMethod("doPost", int.class, String.class,
                    Map.class, Map.class, List.class, Object.class);
            Object call = doPost.invoke(api, 1024 * 1024, cachedHost + path,
                    query, new LinkedHashMap<>(), new ArrayList<>(), null);
            if (call != null) {
                Method execute = call.getClass().getMethod("execute");
                execute.setAccessible(true);
                Object response = execute.invoke(call);
                Object body = invokeBody(response);
                if (body != null) return String.valueOf(body);
            }
        } catch (Throwable t) {
            Logger.printDebug(() -> "doPost fallback to GET: " + t.getMessage());
        }
        return AccountCountry.fetch(path, query);
    }

    private static Object invokeBody(Object response) {
        if (response == null) return null;
        Object raw = null;
        try {
            Method m = response.getClass().getMethod("body");
            m.setAccessible(true);
            raw = m.invoke(response);
        } catch (Throwable ignored) {}
        if (raw == null) {
            for (Class<?> at = response.getClass(); at != null && at != Object.class; at = at.getSuperclass()) {
                for (Field f : at.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType() != Object.class) continue;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(response);
                        if (v != null) {
                            raw = v;
                            break;
                        }
                    } catch (Throwable ignored) {}
                }
                if (raw != null) break;
            }
        }
        if (raw == null) return null;
        if (raw instanceof String) return raw;
        try {
            Class<?> typedInput = Class.forName("com.bytedance.retrofit2.mime.TypedInput");
            if (typedInput.isInstance(raw)) {
                try (java.io.InputStream is = (java.io.InputStream) typedInput.getMethod("in").invoke(raw)) {
                    if (is == null) return null;
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
                    return new String(baos.toByteArray(), StandardCharsets.UTF_8);
                }
            }
        } catch (Throwable ignored) {}
        return String.valueOf(raw);
    }

    private static Object networkApi() throws Exception {
        if (cachedNetworkApi != null) return cachedNetworkApi;
        String foundHost = null;
        try {
            for (Field field : Class.forName(API).getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
                field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof String && ((String) value).startsWith("https://")
                        && ((String) value).indexOf('/', 8) < 0) {
                    foundHost = (String) value;
                    break;
                }
            }
        } catch (ClassNotFoundException ignored) {}
        if (foundHost == null) foundHost = "https://api22-normal-c-useast1a.tiktokv.com";
        Object api = null;
        try {
            Class<?> managerType = Class.forName(SERVICE_MANAGER);
            Object manager = managerType.getMethod("get").invoke(null);
            Class<?> retrofitService = Class.forName(RETROFIT_SERVICE);
            Object factory = managerType.getMethod("getService", Class.class).invoke(manager, retrofitService);
            Object retrofit = retrofitService.getMethod("createNewRetrofit", String.class).invoke(factory, foundHost);
            api = Class.forName(RETROFIT).getMethod("create", Class.class)
                    .invoke(retrofit, Class.forName(NETWORK_API));
        } catch (ClassNotFoundException ignored) {
            api = AccountCountry.createLiteNetworkApi(foundHost);
        }
        cachedHost = foundHost;
        cachedNetworkApi = api;
        return api;
    }

    private static String cleanUsername(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        int at = s.indexOf("/@");
        if (at >= 0) {
            s = s.substring(at + 2);
            int q = s.indexOf('?');
            if (q >= 0) s = s.substring(0, q);
            int sl = s.indexOf('/');
            if (sl >= 0) s = s.substring(0, sl);
        }
        return s.startsWith("@") ? s.substring(1) : s;
    }

    private static Map<String, String> parseQuery(String qs) {
        Map<String, String> map = new LinkedHashMap<>();
        if (qs == null || qs.isEmpty()) return map;
        for (String pair : qs.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                String k = URLDecoder.decode(pair.substring(0, eq), "UTF-8");
                String v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                map.put(k, v);
            } catch (Throwable ignored) {}
        }
        return map;
    }
}
