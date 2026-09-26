package local.qwenimage.mobile;

import android.content.Context;
import android.net.Uri;
import android.webkit.MimeTypeMap;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;

/** Generated images and their task details in app-private storage. */
final class ResultHistory {
    static final class Entry {
        final String id;
        final long createdAt;
        final String prompt;
        final long seed;
        final boolean edit;
        final int unetVariant;
        final String server;
        final int width;
        final int height;
        final int aspectIndex;
        final int resolutionIndex;
        final int steps;
        final String encoder;
        final File reference;
        final File reference2;
        final boolean reusable;
        final File image;

        Entry(String id, JSONObject metadata, File directory) throws Exception {
            this.id = id;
            createdAt = metadata.getLong("createdAt");
            prompt = metadata.getString("prompt");
            seed = metadata.getLong("seed");
            edit = metadata.getBoolean("edit");
            unetVariant = metadata.has("unetVariant") ? metadata.getInt("unetVariant")
                    : (metadata.optBoolean("ggufUnet", false) ? 1 : 0);
            server = metadata.optString("server", "");
            width = metadata.optInt("width", 0);
            height = metadata.optInt("height", 0);
            aspectIndex = metadata.optInt("aspectIndex", -1);
            resolutionIndex = metadata.optInt("resolutionIndex", -1);
            steps = metadata.optInt("steps", 0);
            encoder = metadata.optString("encoder", "");
            String ref = metadata.optString("reference", "");
            String ref2 = metadata.optString("reference2", "");
            reference = ref.isEmpty() ? null : new File(directory, ref);
            reference2 = ref2.isEmpty() ? null : new File(directory, ref2);
            image = new File(directory, id + ".png");
            reusable = metadata.has("steps") && !server.isEmpty() && !encoder.isEmpty()
                    && aspectIndex >= 0 && resolutionIndex >= 0 && unetVariant != 1
                    && (!edit || (reference != null && reference.isFile()))
                    && (reference2 == null || reference2.isFile());
        }
    }

    private final Context context;
    private final File directory;

    ResultHistory(Context context) {
        this.context = context.getApplicationContext();
        directory = new File(context.getFilesDir(), "result-history");
    }

    Entry add(String server, String prompt, boolean edit, Uri reference, Uri reference2,
              int width, int height, int aspectIndex, int resolutionIndex, int steps,
              long seed, String encoder, int unetVariant, byte[] image) throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建历史目录");
        String id = UUID.randomUUID().toString();
        long createdAt = System.currentTimeMillis();
        File imageFile = new File(directory, id + ".png");
        File metadataFile = new File(directory, id + ".json");
        File temporary = new File(directory, id + ".tmp");
        File copiedReference = null;
        File copiedReference2 = null;
        try {
            if (edit) {
                copiedReference = copyReference(reference, id, 1);
                if (reference2 != null) copiedReference2 = copyReference(reference2, id, 2);
            }
            try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(image); }
            if (!temporary.renameTo(imageFile)) throw new IOException("无法保存历史图片");
            JSONObject metadata = new JSONObject();
            metadata.put("createdAt", createdAt);
            metadata.put("prompt", prompt);
            metadata.put("seed", seed);
            metadata.put("edit", edit);
            metadata.put("unetVariant", unetVariant);
            metadata.put("server", server);
            metadata.put("width", width);
            metadata.put("height", height);
            metadata.put("aspectIndex", aspectIndex);
            metadata.put("resolutionIndex", resolutionIndex);
            metadata.put("steps", steps);
            metadata.put("encoder", encoder);
            if (copiedReference != null) metadata.put("reference", copiedReference.getName());
            if (copiedReference2 != null) metadata.put("reference2", copiedReference2.getName());
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write(metadata.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!temporary.renameTo(metadataFile)) throw new IOException("无法保存任务信息");
            return new Entry(id, metadata, directory);
        } catch (Exception e) {
            temporary.delete();
            imageFile.delete();
            metadataFile.delete();
            if (copiedReference != null) copiedReference.delete();
            if (copiedReference2 != null) copiedReference2.delete();
            throw e;
        }
    }

    private File copyReference(Uri uri, String id, int number) throws IOException {
        if (uri == null) throw new IOException("历史任务缺少参考图");
        String mime = context.getContentResolver().getType(uri);
        String extension = mime == null ? MimeTypeMap.getFileExtensionFromUrl(uri.toString())
                : MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        if (extension == null || extension.isEmpty()) extension = "jpg";
        File target = new File(directory, id + "-ref" + number + "." + extension);
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(target)) {
            if (in == null) throw new IOException("无法读取参考图");
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        } catch (Exception e) {
            target.delete();
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("无法读取参考图", e);
        }
        return target;
    }

    File copyReferenceForReuse(File source) throws IOException {
        if (source == null) return null;
        if (!source.isFile()) throw new IOException("参考图已丢失");
        File drafts = new File(context.getCacheDir(), "reused-references");
        if (!drafts.isDirectory() && !drafts.mkdirs()) throw new IOException("无法创建参考图缓存");
        String name = source.getName();
        String extension = name.contains(".") ? name.substring(name.lastIndexOf('.')) : ".jpg";
        File target = new File(drafts, UUID.randomUUID() + extension);
        Files.copy(source.toPath(), target.toPath());
        return target;
    }

    void remove(Entry entry) throws IOException {
        Files.deleteIfExists(new File(directory, entry.id + ".json").toPath());
        Files.deleteIfExists(entry.image.toPath());
        if (entry.reference != null) Files.deleteIfExists(entry.reference.toPath());
        if (entry.reference2 != null) Files.deleteIfExists(entry.reference2.toPath());
    }

    ArrayList<Entry> load() {
        ArrayList<Entry> entries = new ArrayList<>();
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return entries;
        for (File metadataFile : files) {
            String id = metadataFile.getName().substring(0, metadataFile.getName().length() - 5);
            File imageFile = new File(directory, id + ".png");
            if (!imageFile.isFile()) continue;
            try {
                JSONObject metadata = new JSONObject(new String(Files.readAllBytes(metadataFile.toPath()), StandardCharsets.UTF_8));
                entries.add(new Entry(id, metadata, directory));
            } catch (Exception ignored) { /* Skip an incomplete or damaged record. */ }
        }
        entries.sort(Comparator.comparingLong((Entry item) -> item.createdAt).reversed());
        return entries;
    }
}
