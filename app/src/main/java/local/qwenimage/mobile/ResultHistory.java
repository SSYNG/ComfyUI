package local.qwenimage.mobile;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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
        final File image;

        Entry(String id, long createdAt, String prompt, long seed, boolean edit, File image) {
            this.id = id;
            this.createdAt = createdAt;
            this.prompt = prompt;
            this.seed = seed;
            this.edit = edit;
            this.image = image;
        }
    }

    private final File directory;

    ResultHistory(Context context) {
        directory = new File(context.getFilesDir(), "result-history");
    }

    Entry add(String prompt, long seed, boolean edit, byte[] image) throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建历史目录");
        String id = UUID.randomUUID().toString();
        long createdAt = System.currentTimeMillis();
        File imageFile = new File(directory, id + ".png");
        File metadataFile = new File(directory, id + ".json");
        File temporary = new File(directory, id + ".tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(image); }
            if (!temporary.renameTo(imageFile)) throw new IOException("无法保存历史图片");
            JSONObject metadata = new JSONObject();
            metadata.put("createdAt", createdAt);
            metadata.put("prompt", prompt);
            metadata.put("seed", seed);
            metadata.put("edit", edit);
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                out.write(metadata.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!temporary.renameTo(metadataFile)) throw new IOException("无法保存任务信息");
            return new Entry(id, createdAt, prompt, seed, edit, imageFile);
        } catch (Exception e) {
            temporary.delete();
            imageFile.delete();
            metadataFile.delete();
            throw e;
        }
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
                entries.add(new Entry(id, metadata.getLong("createdAt"), metadata.getString("prompt"),
                        metadata.getLong("seed"), metadata.getBoolean("edit"), imageFile));
            } catch (Exception ignored) { /* Skip an incomplete or damaged record. */ }
        }
        entries.sort(Comparator.comparingLong((Entry item) -> item.createdAt).reversed());
        return entries;
    }
}
