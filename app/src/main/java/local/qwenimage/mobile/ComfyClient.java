package local.qwenimage.mobile;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.webkit.MimeTypeMap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Direct LAN client for ComfyUI's built-in API. All methods must run off the UI thread. */
final class ComfyClient {
    interface StatusListener {
        void update(String message);
        default void stage(String name, int percent, String detail, long etaSeconds) { }
        default void queued(String promptId) { }
        default boolean isCanceled() { return false; }
    }

    private final Context context;
    private final String baseUrl;

    ComfyClient(Context context, String server) {
        this.context = context.getApplicationContext();
        String value = server.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            throw new IllegalArgumentException("服务地址须以 http:// 开头");
        }
        try {
            URL parsed = new URL(value);
            if (parsed.getHost().isEmpty() || !parsed.getPath().isEmpty() || parsed.getQuery() != null
                    || parsed.getUserInfo() != null) {
                throw new IllegalArgumentException("请输入完整服务地址，例如 http://192.168.1.10:8188");
            }
        } catch (java.net.MalformedURLException e) {
            throw new IllegalArgumentException("服务地址格式无效", e);
        }
        baseUrl = value;
    }

    void checkConnection() throws Exception {
        HttpURLConnection connection = open("GET", "/system_stats");
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        JSONObject result = new JSONObject(new String(readResponse(connection), StandardCharsets.UTF_8));
        if (!result.has("system")) throw new IOException("服务响应不是 ComfyUI");
    }

    byte[] generate(boolean edit, String prompt, Uri reference, Uri reference2, int width, int height,
                    int steps, long seed, String encoder, int unetVariant, StatusListener listener) throws Exception {
        if (listener.isCanceled()) throw new InterruptedException("任务已取消");
        JSONObject workflow = loadWorkflow(edit ? "qwen_edit_api.json" : "qwen_t2i_api.json");
        if (unetVariant == 2) {
            workflow.getJSONObject("1").getJSONObject("inputs")
                    .put("unet_name", "qwen-image-2.1-UC-int8_convrot.safetensors");
        } else if (unetVariant != 0) {
            throw new IllegalArgumentException("未知的 UNet 模型选项");
        }
        workflow.getJSONObject("2").getJSONObject("inputs").put("clip_name", encoder);
        String editInstruction = reference2 == null
                ? "以<image1>为原图，按以下要求编辑，保留未要求修改的内容：\n"
                : "以<image1>为主图，参考<image2>，按以下要求编辑：\n";
        workflow.getJSONObject("4").getJSONObject("inputs")
                .put("prompt", edit ? editInstruction + prompt : prompt);
        workflow.getJSONObject("7").getJSONObject("inputs").put("steps", steps).put("seed", seed);

        if (edit) {
            if (reference == null) throw new IllegalArgumentException("请先选择参考图");
            listener.stage("上传参考图", 1, "正在发送图片到电脑", -1);
            String uploadedName = uploadImage(reference);
            workflow.getJSONObject("5").getJSONObject("inputs").put("image", uploadedName);
            if (reference2 != null) {
                if (listener.isCanceled()) throw new InterruptedException("任务已取消");
                listener.stage("上传参考图", 1, "正在发送第 2 张图片到电脑", -1);
                String uploadedName2 = uploadImage(reference2);
                workflow.put("10", new JSONObject().put("class_type", "LoadImage")
                        .put("inputs", new JSONObject().put("image", uploadedName2)));
                workflow.getJSONObject("4").getJSONObject("inputs")
                        .put("images.image_2", new JSONArray().put("10").put(0));
            }
        } else {
            workflow.getJSONObject("5").getJSONObject("inputs")
                    .put("width", width).put("height", height);
        }
        if (listener.isCanceled()) throw new InterruptedException("任务已取消");

        String clientId = UUID.randomUUID().toString();
        String promptId = UUID.randomUUID().toString();
        JSONObject requestBody = new JSONObject().put("prompt", workflow).put("client_id", clientId).put("prompt_id", promptId);
        ProgressTracker tracker = new ProgressTracker(promptId, listener);
        ComfyProgressSocket socket = null;
        try {
            try {
                socket = ComfyProgressSocket.connect(baseUrl, clientId, new ComfyProgressSocket.Listener() {
                    @Override public void onEvent(JSONObject event) { tracker.onEvent(event); }
                    @Override public void onDisconnect() { listener.update("实时进度连接中断，继续查询结果"); }
                });
            } catch (Exception ignored) {
                listener.update("实时进度暂不可用，继续查询结果");
            }
            tracker.stage("提交任务", 2, "发送到 ComfyUI", -1);
            JSONObject queued = new JSONObject(new String(
                    request("POST", "/prompt", requestBody.toString().getBytes(StandardCharsets.UTF_8), "application/json"),
                    StandardCharsets.UTF_8));
            String returnedId = queued.optString("prompt_id");
            if (!promptId.equals(returnedId)) throw new IOException("提交失败：" + queued);
            listener.queued(promptId);
            tracker.stage("排队中", 3, "等待 ComfyUI 开始", -1);

            long deadline = System.currentTimeMillis() + 20L * 60 * 1000;
            while (System.currentTimeMillis() < deadline) {
                if (Thread.currentThread().isInterrupted() || listener.isCanceled()) {
                    cancelJob(promptId);
                    throw new InterruptedException("任务已取消");
                }
                JSONObject all = new JSONObject(new String(
                        request("GET", "/history/" + promptId, null, null), StandardCharsets.UTF_8));
                JSONObject job = all.optJSONObject(promptId);
                if (job != null) {
                    JSONObject outputs = job.optJSONObject("outputs");
                    if (outputs != null) {
                        JSONObject saveOutput = outputs.optJSONObject("9");
                        JSONArray images = saveOutput == null ? null : saveOutput.optJSONArray("images");
                        if (images != null && images.length() > 0) {
                            JSONObject image = images.getJSONObject(0);
                            tracker.stage("取回图片", 98, "正在传回手机", -1);
                            String path = "/view?filename=" + encode(image.getString("filename"))
                                    + "&subfolder=" + encode(image.optString("subfolder"))
                                    + "&type=" + encode(image.optString("type", "output"));
                            byte[] result = request("GET", path, null, null);
                            tracker.stage("完成", 100, "图片已生成", 0);
                            return result;
                        }
                    }
                    JSONObject status = job.optJSONObject("status");
                    if (status != null) {
                        if ("error".equals(status.optString("status_str"))) throw new IOException("ComfyUI 执行失败：" + status);
                        if (status.optBoolean("completed")) throw new IOException("任务已结束，但没有生成图片");
                    }
                }
                Thread.sleep(2000);
            }
            throw new IOException("等待超过 20 分钟；可到电脑 ComfyUI 查看任务 " + promptId);
        } finally {
            if (socket != null) socket.close();
        }
    }

    void cancelJob(String promptId) throws IOException {
        try {
            JSONObject pending = new JSONObject().put("delete", new JSONArray().put(promptId));
            request("POST", "/queue", pending.toString().getBytes(StandardCharsets.UTF_8), "application/json");
            JSONObject running = new JSONObject().put("prompt_id", promptId);
            request("POST", "/interrupt", running.toString().getBytes(StandardCharsets.UTF_8), "application/json");
        } catch (org.json.JSONException e) {
            throw new IOException("取消任务失败", e);
        }
    }

    private static final class ProgressTracker {
        private final String promptId;
        private final StatusListener listener;
        private int shownPercent;
        private int lastStep;
        private long lastStepAt;
        private long samplerStartedAt;
        private double stepMillis = Double.NaN;

        ProgressTracker(String promptId, StatusListener listener) {
            this.promptId = promptId;
            this.listener = listener;
        }

        synchronized void stage(String name, int percent, String detail, long etaSeconds) {
            shownPercent = Math.max(shownPercent, Math.min(100, percent));
            listener.stage(name, shownPercent, detail, etaSeconds);
        }

        synchronized void onEvent(JSONObject event) {
            JSONObject data = event.optJSONObject("data");
            if (data == null || !promptId.equals(data.optString("prompt_id"))) return;
            String type = event.optString("type");
            if ("execution_start".equals(type)) stage("开始执行", 4, "准备工作流", -1);
            else if ("executing".equals(type)) {
                String node = data.optString("node");
                switch (node) {
                    case "1": case "2": case "3": case "5": stage("加载模型与素材", 8, "准备生成所需文件", -1); break;
                    case "4": stage("编码提示词", 15, "理解文字与参考图", -1); break;
                    case "6": stage("准备采样", 18, "初始化模型缓存", -1); break;
                    case "7":
                        samplerStartedAt = System.currentTimeMillis();
                        stage("采样中", 20, "等待第 1 步", -1);
                        break;
                    case "8": stage("解码图片", 92, "将潜空间结果转成图片", -1); break;
                    case "9": stage("保存图片", 97, "写入电脑输出目录", -1); break;
                    default: break;
                }
            } else if ("progress".equals(type) && "7".equals(data.optString("node"))) {
                int value = data.optInt("value");
                int max = data.optInt("max");
                if (max <= 0) return;
                long now = System.currentTimeMillis();
                if (value > lastStep) {
                    long elapsed = lastStepAt > 0 ? now - lastStepAt : now - samplerStartedAt;
                    if (elapsed > 0 && samplerStartedAt > 0) {
                        double observed = (double) elapsed / (value - lastStep);
                        stepMillis = Double.isNaN(stepMillis) ? observed : stepMillis * 0.65 + observed * 0.35;
                    }
                    lastStep = value;
                    lastStepAt = now;
                }
                long eta = Double.isNaN(stepMillis) ? -1 : Math.round((max - value) * stepMillis / 1000.0) + 5;
                int total = 20 + Math.round(70f * Math.min(value, max) / max);
                stage("采样中", total, "第 " + value + " / " + max + " 步（采样 "
                        + Math.round(100f * value / max) + "%）", eta);
            } else if ("execution_success".equals(type)) stage("准备取图", 97, "生成完成，等待结果", -1);
            else if ("execution_error".equals(type)) stage("执行失败", shownPercent, "查看下方错误", -1);
            else if ("execution_interrupted".equals(type)) stage("已取消", shownPercent, "任务已中断", 0);
        }
    }

    private JSONObject loadWorkflow(String assetName) throws Exception {
        try (InputStream in = context.getAssets().open(assetName)) {
            return new JSONObject(new String(readFully(in), StandardCharsets.UTF_8));
        }
    }

    private String uploadImage(Uri uri) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        String mime = resolver.getType(uri);
        if (mime == null && "file".equals(uri.getScheme())) {
            String extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString());
            mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        }
        if (mime == null || !mime.startsWith("image/")) throw new IOException("请选择图片文件");
        String suffix = mime.equals("image/png") ? ".png" : mime.equals("image/webp") ? ".webp" : ".jpg";
        String filename = "qwen_mobile_" + UUID.randomUUID().toString().replace("-", "") + suffix;
        String boundary = "QwenMobile" + UUID.randomUUID().toString().replace("-", "");
        HttpURLConnection connection = open("POST", "/upload/image");
        connection.setDoOutput(true);
        connection.setChunkedStreamingMode(64 * 1024);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try (OutputStream out = connection.getOutputStream(); InputStream in = resolver.openInputStream(uri)) {
            if (in == null) throw new IOException("无法读取参考图");
            write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"" + filename
                    + "\"\r\nContent-Type: " + mime + "\r\n\r\n");
            copy(in, out);
            write(out, "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"type\"\r\n\r\ninput\r\n");
            write(out, "--" + boundary + "--\r\n");
        }
        JSONObject response = new JSONObject(new String(readResponse(connection), StandardCharsets.UTF_8));
        String result = response.optString("name");
        if (result.isEmpty()) throw new IOException("图片上传失败：" + response);
        String subfolder = response.optString("subfolder");
        return subfolder.isEmpty() ? result : subfolder + "/" + result;
    }

    private byte[] request(String method, String path, byte[] body, String contentType) throws IOException {
        HttpURLConnection connection = open(method, path);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", contentType);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = connection.getOutputStream()) { out.write(body); }
        }
        return readResponse(connection);
    }

    private HttpURLConnection open(String method, String path) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(120000);
        connection.setUseCaches(false);
        return connection;
    }

    private static byte[] readResponse(HttpURLConnection connection) throws IOException {
        try {
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            byte[] data = stream == null ? new byte[0] : readFully(stream);
            if (code >= 400) {
                String message = new String(data, StandardCharsets.UTF_8);
                throw new IOException("HTTP " + code + "：" + message.substring(0, Math.min(1000, message.length())));
            }
            return data;
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readFully(InputStream in) throws IOException {
        try (InputStream source = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            copy(source, out);
            return out.toByteArray();
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
    }

    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(String value) throws IOException {
        return URLEncoder.encode(value, "UTF-8");
    }
}
